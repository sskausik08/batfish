package org.batfish.datamodel;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaDescription;

/**
 * Represents a bgp process on a router
 */
@JsonSchemaDescription("A BGP routing process")
public class BgpProcess implements Serializable {

   private static final String GENERATED_ROUTES_VAR = "generatedRoutes";

   private static final String NEIGHBORS_VAR = "neighbors";

   private static final String ROUTER_ID_VAR = "routerId";

   /**
    *
    */
   private static final long serialVersionUID = 1L;

   /**
    * The set of <i>neighbor-independent</i> generated routes that may be
    * advertised by this process if permitted by their respective generation
    * policies
    */
   private Set<GeneratedRoute> _generatedRoutes;

   /**
    * A map of all the bgp neighbors with which the router owning this process
    * is configured to peer, keyed by prefix
    */
   private Map<Prefix, BgpNeighbor> _neighbors;

   private transient PrefixSpace _originationSpace;

   private Ip _routerId;

   /**
    * Constructs a BgpProcess
    */
   public BgpProcess() {
      _neighbors = new HashMap<>();
      _generatedRoutes = new HashSet<>();
   }

   /**
    * @return {@link #_generatedRoutes}
    */
   @JsonProperty(GENERATED_ROUTES_VAR)
   @JsonPropertyDescription("IPV4 routes generated in the BGP RIB that are not imported into the main RIB for this VRF")
   public Set<GeneratedRoute> getGeneratedRoutes() {
      return _generatedRoutes;
   }

   /**
    * @return {@link #_neighbors}
    */
   @JsonProperty(NEIGHBORS_VAR)
   @JsonPropertyDescription("Neighbor relationships configured for this BGP process")
   public Map<Prefix, BgpNeighbor> getNeighbors() {
      return _neighbors;
   }

   @JsonIgnore
   public PrefixSpace getOriginationSpace() {
      return _originationSpace;
   }

   @JsonProperty(ROUTER_ID_VAR)
   @JsonPropertyDescription("The configured router ID for this BGP process. Note that it can be overridden for individual neighbors.")
   public Ip getRouterId() {
      return _routerId;
   }

   @JsonProperty(GENERATED_ROUTES_VAR)
   public void setGeneratedRoutes(Set<GeneratedRoute> generatedRoutes) {
      _generatedRoutes = generatedRoutes;
   }

   @JsonProperty(NEIGHBORS_VAR)
   public void setNeighbors(Map<Prefix, BgpNeighbor> neighbors) {
      _neighbors = neighbors;
   }

   public void setOriginationSpace(PrefixSpace originationSpace) {
      _originationSpace = originationSpace;
   }

   @JsonProperty(ROUTER_ID_VAR)
   public void setRouterId(Ip routerId) {
      _routerId = routerId;
   }

}
