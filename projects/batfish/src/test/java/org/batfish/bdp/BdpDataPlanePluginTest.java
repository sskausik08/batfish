package org.batfish.bdp;

import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.batfish.common.BatfishException;
import org.batfish.common.BdpOscillationException;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.BgpProcess;
import org.batfish.datamodel.BgpRoute;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpAccessListLine;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.OriginType;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.Route;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.SourceNat;
import org.batfish.datamodel.Vrf;
import org.batfish.datamodel.collections.RoutesByVrf;
import org.batfish.main.Batfish;
import org.batfish.main.BatfishTestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

/** Tests for {@link BdpDataPlanePlugin}. */
public class BdpDataPlanePluginTest {

  private static String TESTRIGS_PREFIX = "org/batfish/grammar/cisco/testrigs/";

  @Rule public TemporaryFolder _folder = new TemporaryFolder();
  @Rule public ExpectedException _thrown = ExpectedException.none();

  @Test(timeout = 5000)
  public void testComputeFixedPoint() throws IOException {
    SortedMap<String, Configuration> configurations = new TreeMap<>();
    // creating configurations with no vrfs
    configurations.put(
        "h1", BatfishTestUtils.createTestConfiguration("h1", ConfigurationFormat.HOST, "eth0"));
    configurations.put(
        "h2", BatfishTestUtils.createTestConfiguration("h2", ConfigurationFormat.HOST, "e0"));
    Batfish batfish = BatfishTestUtils.getBatfish(configurations, _folder);
    BdpDataPlanePlugin dataPlanePlugin = new BdpDataPlanePlugin();
    dataPlanePlugin.initialize(batfish);

    // Test that compute Data Plane finishes in a finite time
    dataPlanePlugin.computeDataPlane(false);
  }

  private static Flow makeFlow() {
    Flow.Builder builder = new Flow.Builder();
    builder.setSrcIp(new Ip("1.2.3.4"));
    builder.setIngressNode("foo");
    builder.setTag("TEST");
    return builder.build();
  }

  @SuppressWarnings("unused")
  private static IpAccessListLine makeAclLine(LineAction action) {
    IpAccessListLine aclLine = new IpAccessListLine();
    aclLine.setAction(action);
    return aclLine;
  }

  private static IpAccessList makeAcl(String name, LineAction action) {
    IpAccessListLine aclLine = new IpAccessListLine();
    aclLine.setAction(action);
    return new IpAccessList(name, singletonList(aclLine));
  }

  @Test
  public void testApplySourceNatSingleAclMatch() {
    Flow flow = makeFlow();

    SourceNat nat = new SourceNat();
    nat.setAcl(makeAcl("accept", LineAction.ACCEPT));
    nat.setPoolIpFirst(new Ip("4.5.6.7"));

    Flow transformed = BdpDataPlanePlugin.applySourceNat(flow, singletonList(nat));
    assertThat(transformed.getSrcIp(), equalTo(new Ip("4.5.6.7")));
  }

  @Test
  public void testApplySourceNatSingleAclNoMatch() {
    Flow flow = makeFlow();

    SourceNat nat = new SourceNat();
    nat.setAcl(makeAcl("reject", LineAction.REJECT));
    nat.setPoolIpFirst(new Ip("4.5.6.7"));

    Flow transformed = BdpDataPlanePlugin.applySourceNat(flow, singletonList(nat));
    assertThat(transformed, is(flow));
  }

  @Test
  public void testApplySourceNatFirstMatchWins() {
    Flow flow = makeFlow();

    SourceNat nat = new SourceNat();
    nat.setAcl(makeAcl("firstAccept", LineAction.ACCEPT));
    nat.setPoolIpFirst(new Ip("4.5.6.7"));

    SourceNat secondNat = new SourceNat();
    secondNat.setAcl(makeAcl("secondAccept", LineAction.ACCEPT));
    secondNat.setPoolIpFirst(new Ip("4.5.6.8"));

    Flow transformed = BdpDataPlanePlugin.applySourceNat(flow, Lists.newArrayList(nat, secondNat));
    assertThat(transformed.getSrcIp(), equalTo(new Ip("4.5.6.7")));
  }

  @Test
  public void testApplySourceNatLateMatchWins() {
    Flow flow = makeFlow();

    SourceNat nat = new SourceNat();
    nat.setAcl(makeAcl("rejectAll", LineAction.REJECT));
    nat.setPoolIpFirst(new Ip("4.5.6.7"));

    SourceNat secondNat = new SourceNat();
    secondNat.setAcl(makeAcl("acceptAnyway", LineAction.ACCEPT));
    secondNat.setPoolIpFirst(new Ip("4.5.6.8"));

    Flow transformed = BdpDataPlanePlugin.applySourceNat(flow, Lists.newArrayList(nat, secondNat));
    assertThat(transformed.getSrcIp(), equalTo(new Ip("4.5.6.8")));
  }

  @Test
  public void testApplySourceNatInvalidAclThrows() {
    Flow flow = makeFlow();

    SourceNat nat = new SourceNat();
    nat.setAcl(makeAcl("matchAll", LineAction.ACCEPT));

    _thrown.expect(BatfishException.class);
    _thrown.expectMessage("missing NAT address or pool");
    BdpDataPlanePlugin.applySourceNat(flow, singletonList(nat));
  }

  @Test
  public void testBgpCompareOriginId() {
    String hostname = "r1";
    Configuration c =
        BatfishTestUtils.createTestConfiguration(hostname, ConfigurationFormat.CISCO_IOS);
    BgpProcess proc = new BgpProcess();
    c.getVrfs().computeIfAbsent(Configuration.DEFAULT_VRF_NAME, Vrf::new).setBgpProcess(proc);
    Map<String, Node> nodes = new HashMap<String, Node>();
    Node node = new Node(c, nodes);
    nodes.put(hostname, node);
    VirtualRouter vr = new VirtualRouter(hostname, c, nodes);
    BgpBestPathRib bbr = new BgpBestPathRib(vr);
    BgpMultipathRib bmr = new BgpMultipathRib(vr);
    Prefix p = new Prefix("0.0.0.0/0");
    BgpRoute.Builder b = new BgpRoute.Builder().setNetwork(p).setProtocol(RoutingProtocol.IBGP);

    /*
     *  Initialize with different originator ips, which should not affect comparison of routes with
     *  different origin type.
     */
    Map<OriginType, List<BgpRoute>> routesByOriginType = new LinkedHashMap<>();
    for (OriginType originType : OriginType.values()) {
      List<BgpRoute> routes =
          routesByOriginType.computeIfAbsent(originType, o -> new ArrayList<>());
      routes.add(b.setOriginatorIp(Ip.ZERO).setOriginType(originType).build());
      routes.add(b.setOriginatorIp(Ip.MAX).setOriginType(originType).build());
    }

    /*
     * Whenever origin type is different, it should be overriding factor in preference.
     */
    for (OriginType o1 : OriginType.values()) {
      List<BgpRoute> lhsList = routesByOriginType.get(o1);
      for (OriginType o2 : OriginType.values()) {
        List<BgpRoute> rhsList = routesByOriginType.get(o2);
        for (BgpRoute lhs : lhsList) {
          for (BgpRoute rhs : rhsList) {
            if (o1.getPreference() > o2.getPreference()) {
              assertThat(bbr.comparePreference(lhs, rhs), greaterThan(0));
              assertThat(bmr.comparePreference(lhs, rhs), greaterThan(0));
            } else if (o1.getPreference() < o2.getPreference()) {
              assertThat(bbr.comparePreference(lhs, rhs), lessThan(0));
              assertThat(bmr.comparePreference(lhs, rhs), lessThan(0));
            }
          }
        }
      }
    }
  }

  @Test
  public void testBgpOscillation() throws IOException {
    String testrigName = "bgp-oscillation";
    String[] configurationNames = new String[] {"r1", "r2", "r3"};
    Batfish batfish =
        BatfishTestUtils.getBatfishFromTestrigResource(
            TESTRIGS_PREFIX + testrigName, configurationNames, null, null, null, null, _folder);
    BdpDataPlanePlugin dataPlanePlugin = new BdpDataPlanePlugin();
    dataPlanePlugin.initialize(batfish);

    _thrown.expect(BdpOscillationException.class);
    dataPlanePlugin.computeDataPlane(false);
  }

  @Test
  public void testEbgpAcceptSameNeighborID() throws IOException {
    String testrigName = "ebgp-accept-routerid-match";
    String[] configurationNames = new String[] {"r1", "r2", "r3"};
    Batfish batfish =
        BatfishTestUtils.getBatfishFromTestrigResource(
            TESTRIGS_PREFIX + testrigName, configurationNames, null, null, null, null, _folder);
    BdpDataPlanePlugin dataPlanePlugin = new BdpDataPlanePlugin();
    dataPlanePlugin.initialize(batfish);
    dataPlanePlugin.computeDataPlane(false);
    SortedMap<String, SortedMap<String, SortedSet<AbstractRoute>>> routes =
        dataPlanePlugin.getRoutes();

    SortedSet<AbstractRoute> r1Routes = routes.get("r1").get(Configuration.DEFAULT_VRF_NAME);
    SortedSet<AbstractRoute> r3Routes = routes.get("r3").get(Configuration.DEFAULT_VRF_NAME);
    Set<Prefix> r1Prefixes = r1Routes.stream().map(r -> r.getNetwork()).collect(Collectors.toSet());
    Set<Prefix> r3Prefixes = r3Routes.stream().map(r -> r.getNetwork()).collect(Collectors.toSet());
    Prefix r1Loopback0Prefix = new Prefix("1.0.0.1/32");
    Prefix r3Loopback0Prefix = new Prefix("3.0.0.3/32");

    // Ensure that r3loopback was accepted by r1
    assertThat(r3Loopback0Prefix, isIn(r1Prefixes));
    // Check the other direction (r1loopback is accepted by r3)
    assertThat(r1Loopback0Prefix, isIn(r3Prefixes));
  }

  @Test
  public void testContainsRoute() {
    String hostname = "r1";
    Configuration c =
        BatfishTestUtils.createTestConfiguration(hostname, ConfigurationFormat.CISCO_IOS);
    BgpProcess proc = new BgpProcess();
    c.getVrfs().computeIfAbsent(Configuration.DEFAULT_VRF_NAME, Vrf::new).setBgpProcess(proc);
    Map<String, Node> nodes = new HashMap<String, Node>();
    Node node = new Node(c, nodes);
    nodes.put(hostname, node);
    VirtualRouter vr = new VirtualRouter(hostname, c, nodes);
    BgpBestPathRib bbr = new BgpBestPathRib(vr);
    BgpMultipathRib bmr = new BgpMultipathRib(vr);
    Ip ip1 = new Ip("1.0.0.0");
    Ip ip2 = new Ip("2.2.0.0");
    BgpRoute.Builder b1 =
        new BgpRoute.Builder()
            .setNextHopIp(Ip.ZERO)
            .setOriginType(OriginType.INCOMPLETE)
            .setOriginatorIp(Ip.ZERO)
            .setProtocol(RoutingProtocol.BGP);
    BgpRoute.Builder b2 =
        new BgpRoute.Builder()
            .setNextHopIp(Ip.ZERO)
            .setOriginType(OriginType.INCOMPLETE)
            .setOriginatorIp(Ip.MAX)
            .setProtocol(RoutingProtocol.BGP);

    /*
     * Toss a bunch of different routes in each RIB. In the best-path rib, only lower originatorIp
     * routes should remain. In the multipath RIB, all routes should remain.
     */
    for (int i = 8; i <= Prefix.MAX_PREFIX_LENGTH; i++) {
      Prefix p = new Prefix(ip1, i);
      b1.setNetwork(p);
      b2.setNetwork(p);
      bbr.mergeRoute(b1.build());
      bbr.mergeRoute(b2.build());
      bmr.mergeRoute(b1.build());
      bmr.mergeRoute(b2.build());
    }
    for (int i = 16; i <= Prefix.MAX_PREFIX_LENGTH; i++) {
      Prefix p = new Prefix(ip2, i);
      b1.setNetwork(p);
      b2.setNetwork(p);
      bbr.mergeRoute(b1.build());
      bbr.mergeRoute(b2.build());
      bmr.mergeRoute(b1.build());
      bmr.mergeRoute(b2.build());
    }
    for (int i = 8; i <= Prefix.MAX_PREFIX_LENGTH; i++) {
      Prefix p = new Prefix(ip1, i);
      assertTrue(bbr.containsRoute(b1.setNetwork(p).build()));
      b1.setNetwork(p);
      b2.setNetwork(p);
      assertTrue(bbr.containsRoute(b1.build()));
      assertFalse(bbr.containsRoute(b2.build()));
      assertTrue(bmr.containsRoute(b1.build()));
      assertTrue(bmr.containsRoute(b2.build()));
    }
    for (int i = 16; i <= Prefix.MAX_PREFIX_LENGTH; i++) {
      Prefix p = new Prefix(ip2, i);
      b1.setNetwork(p);
      b2.setNetwork(p);
      assertTrue(bbr.containsRoute(b1.build()));
      assertFalse(bbr.containsRoute(b2.build()));
      assertTrue(bmr.containsRoute(b1.build()));
      assertTrue(bmr.containsRoute(b2.build()));
    }
  }

  @Test
  public void testIbgpRejectOwnAs() throws IOException {
    String testrigName = "ibgp-reject-own-as";
    String[] configurationNames = new String[] {"r1", "r2a", "r2b"};
    Batfish batfish =
        BatfishTestUtils.getBatfishFromTestrigResource(
            TESTRIGS_PREFIX + testrigName, configurationNames, null, null, null, null, _folder);
    BdpDataPlanePlugin dataPlanePlugin = new BdpDataPlanePlugin();
    dataPlanePlugin.initialize(batfish);
    dataPlanePlugin.computeDataPlane(false);
    SortedMap<String, SortedMap<String, SortedSet<AbstractRoute>>> routes =
        dataPlanePlugin.getRoutes();
    SortedSet<AbstractRoute> r2aRoutes = routes.get("r2a").get(Configuration.DEFAULT_VRF_NAME);
    SortedSet<AbstractRoute> r2bRoutes = routes.get("r2b").get(Configuration.DEFAULT_VRF_NAME);
    Set<Prefix> r2aPrefixes =
        r2aRoutes.stream().map(r -> r.getNetwork()).collect(Collectors.toSet());
    Set<Prefix> r2bPrefixes =
        r2bRoutes.stream().map(r -> r.getNetwork()).collect(Collectors.toSet());
    Prefix r1Loopback0Prefix = new Prefix("1.0.0.1/32");
    Prefix r1Loopback1Prefix = new Prefix("1.0.0.2/32");
    assertTrue(r2aPrefixes.contains(r1Loopback0Prefix));
    assertTrue(r2aPrefixes.contains(r1Loopback1Prefix));
    /*
     * 1.0.0.2/32 should be accepted r2b as a normal iBGP route forwarded from r1.
     */
    assertTrue(r2bPrefixes.contains(r1Loopback1Prefix));
    /*
     * 1.0.0.1/32 should be rejected by r2b since it already contains AS#2 in its AS-path due to
     * r2a prepending 2 in the matching route-map clause.
     */
    assertFalse(r2bPrefixes.contains(r1Loopback0Prefix));
  }

  @Test
  public void testIbgpRejectSameNeighborID() throws IOException {
    String testrigName = "ibgp-reject-routerid-match";
    String[] configurationNames = new String[] {"r1", "r2", "r3", "r4"};
    Batfish batfish =
        BatfishTestUtils.getBatfishFromTestrigResource(
            TESTRIGS_PREFIX + testrigName, configurationNames, null, null, null, null, _folder);
    BdpDataPlanePlugin dataPlanePlugin = new BdpDataPlanePlugin();
    dataPlanePlugin.initialize(batfish);
    dataPlanePlugin.computeDataPlane(false);
    SortedMap<String, SortedMap<String, SortedSet<AbstractRoute>>> routes =
        dataPlanePlugin.getRoutes();

    SortedSet<AbstractRoute> r2Routes = routes.get("r2").get(Configuration.DEFAULT_VRF_NAME);
    SortedSet<AbstractRoute> r3Routes = routes.get("r3").get(Configuration.DEFAULT_VRF_NAME);
    Set<Prefix> r2Prefixes = r2Routes.stream().map(r -> r.getNetwork()).collect(Collectors.toSet());
    Set<Prefix> r3Prefixes = r3Routes.stream().map(r -> r.getNetwork()).collect(Collectors.toSet());
    // 9.9.9.9/32 is the prefix we test with
    Prefix r1AdvertisedPrefix = new Prefix("9.9.9.9/32");

    // Ensure that the prefix is accepted by r2, because router ids are different
    assertThat(r1AdvertisedPrefix, isIn(r2Prefixes));
    // Ensure that the prefix is rejected by r3, because router ids are the same
    assertThat(r1AdvertisedPrefix, not(isIn(r3Prefixes)));
  }

  @Test
  public void testIosRtStaticMatchesBdp() throws IOException {
    String testrigName = "ios-rt-static-ad";
    String[] configurationNames = new String[] {"r1"};
    String[] routingTableNames = new String[] {"r1"};
    Batfish batfish =
        BatfishTestUtils.getBatfishFromTestrigResource(
            TESTRIGS_PREFIX + testrigName,
            configurationNames,
            null,
            null,
            null,
            routingTableNames,
            _folder);
    BdpDataPlanePlugin dataPlanePlugin = new BdpDataPlanePlugin();
    dataPlanePlugin.initialize(batfish);
    dataPlanePlugin.computeDataPlane(false);
    SortedMap<String, RoutesByVrf> environmentRoutes = batfish.loadEnvironmentRoutingTables();
    SortedMap<String, SortedMap<String, SortedSet<AbstractRoute>>> routes =
        dataPlanePlugin.getRoutes();
    Prefix staticRoutePrefix = new Prefix("10.0.0.0/8");
    SortedSet<AbstractRoute> r1BdpRoutes = routes.get("r1").get(Configuration.DEFAULT_VRF_NAME);
    AbstractRoute r1BdpRoute =
        r1BdpRoutes
            .stream()
            .filter(r -> r.getNetwork().equals(staticRoutePrefix))
            .findFirst()
            .get();
    SortedSet<Route> r1EnvironmentRoutes =
        environmentRoutes.get("r1").get(Configuration.DEFAULT_VRF_NAME);
    Route r1EnvironmentRoute =
        r1EnvironmentRoutes
            .stream()
            .filter(r -> r.getNetwork().equals(staticRoutePrefix))
            .findFirst()
            .get();
    assertThat(
        r1BdpRoute.getAdministrativeCost(), equalTo(r1EnvironmentRoute.getAdministrativeCost()));
    assertThat(r1BdpRoute.getMetric(), equalTo(r1EnvironmentRoute.getMetric()));
    assertThat(r1BdpRoute.getProtocol(), equalTo(r1EnvironmentRoute.getProtocol()));
  }
}
