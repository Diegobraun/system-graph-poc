package com.example.graphmcp.web;

import com.example.graphmcp.graph.GraphClient;
import com.example.graphmcp.graph.Hub;
import com.example.graphmcp.tools.SystemGraphTools;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class GraphViewController {

    private static final String MAP = """
            CALL {
                MATCH (s:Service)
                RETURN collect(s {.name, .repository, .commitSha, .contractSource, .area, .team,
                    indexed: coalesce(s.indexed, false),
                    graphql: s.graphqlSchema IS NOT NULL,
                    endpoints: COUNT { (s)-[:EXPOSES]->() }}) AS services
            }
            CALL {
                MATCH (t:Topic)
                RETURN collect(t.name) AS topics
            }
            CALL {
                MATCH (a:Service)-[:DEPENDS_ON]->(b:Service)
                OPTIONAL MATCH (a)-[r:CALLS]->(e:Endpoint {service: b.name})
                WITH a, b, collect(DISTINCT e.protocol) AS protocols, collect(DISTINCT r.via) AS via, count(e) AS operations
                RETURN collect({source: a.name, target: b.name, protocols: protocols, via: via, operations: operations}) AS calls
            }
            CALL {
                MATCH (s:Service)-[r:PUBLISHES]->(t:Topic)
                RETURN collect(DISTINCT {service: s.name, topic: t.name, via: r.via}) AS publishes
            }
            CALL {
                MATCH (s:Service)-[r:CONSUMES]->(t:Topic)
                RETURN collect(DISTINCT {service: s.name, topic: t.name, via: r.via, group: r.group}) AS consumes
            }
            CALL {
                MATCH (s:Service)-[r:OBSERVED_CONSUMING]->(t:Topic)
                RETURN collect({service: s.name, topic: t.name, activeMembers: r.activeMembers, state: r.state}) AS observedConsuming
            }
            CALL {
                MATCH (a:Service)-[r:OBSERVED_CALLS]->(b:Service)
                RETURN collect({source: a.name, target: b.name, origin: r.source, count: r.count}) AS observedCalls
            }
            RETURN services, topics, calls, publishes, consumes, observedConsuming, observedCalls
            """;

    private static final String INBOUND = """
            CALL {
                MATCH (a:Service)-[:DEPENDS_ON]->(b:Service)
                WHERE b.name IN $local AND NOT a.name IN $local
                OPTIONAL MATCH (a)-[r:CALLS]->(e:Endpoint {service: b.name})
                WITH a, b, collect(DISTINCT e.protocol) AS protocols, collect(DISTINCT r.via) AS via, count(e) AS operations
                RETURN collect({source: a.name, target: b.name, protocols: protocols, via: via, operations: operations}) AS calls
            }
            CALL {
                MATCH (s:Service)-[r:PUBLISHES]->(t:Topic)
                WHERE t.name IN $topics AND NOT s.name IN $local
                RETURN collect(DISTINCT {service: s.name, topic: t.name, via: r.via}) AS publishes
            }
            CALL {
                MATCH (s:Service)-[r:CONSUMES]->(t:Topic)
                WHERE t.name IN $topics AND NOT s.name IN $local
                RETURN collect(DISTINCT {service: s.name, topic: t.name, via: r.via, group: r.group}) AS consumes
            }
            CALL {
                MATCH (a:Service)-[r:OBSERVED_CALLS]->(b:Service)
                WHERE a.name IN $local OR b.name IN $local
                RETURN collect({source: a.name, target: b.name, origin: r.source, count: r.count}) AS observedCalls
            }
            RETURN calls, publishes, consumes, observedCalls
            """;

    private final GraphClient graph;
    private final SystemGraphTools tools;
    private final Map<String, String> links;

    GraphViewController(GraphClient graph, SystemGraphTools tools, @Value("${graph.links:}") String links) {
        this.graph = graph;
        this.tools = tools;
        this.links = parseLinks(links);
    }

    static Map<String, String> parseLinks(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : text.split(",")) {
            int equals = entry.indexOf('=');
            if (equals > 0) {
                result.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
            }
        }
        return result;
    }

    @GetMapping("/map")
    Map<String, Object> map() {
        Map<String, Object> map = new LinkedHashMap<>(graph.read(MAP, Map.of()).getFirst());
        Hub hub = tools.hub();
        if (!hub.enabled()) {
            return map;
        }
        List<Map<String, Object>> services = new ArrayList<>();
        Set<String> local = new HashSet<>();
        for (Object item : (List<?>) map.get("services")) {
            Map<String, Object> service = new LinkedHashMap<>(cast(item));
            services.add(service);
            if (Boolean.TRUE.equals(service.get("indexed"))) {
                local.add((String) service.get("name"));
            }
        }
        Map<String, Object> inbound = hub.graph().read(INBOUND, Map.of("local", List.copyOf(local), "topics", map.get("topics"))).getFirst();
        map.put("calls", concat(map.get("calls"), inbound.get("calls")));
        map.put("publishes", concat(map.get("publishes"), inbound.get("publishes")));
        map.put("consumes", concat(map.get("consumes"), inbound.get("consumes")));
        map.put("observedCalls", concat(map.get("observedCalls"), inbound.get("observedCalls")));
        Map<String, Map<String, Object>> catalog = new HashMap<>();
        hub.catalog().forEach(row -> catalog.put((String) row.get("name"), row));
        Set<String> present = new HashSet<>();
        for (Map<String, Object> service : services) {
            present.add((String) service.get("name"));
            describeExternal(service, catalog);
        }
        for (String name : referencedServices(inbound)) {
            if (present.add(name)) {
                Map<String, Object> service = new LinkedHashMap<>();
                service.put("name", name);
                service.put("indexed", false);
                describeExternal(service, catalog);
                services.add(service);
            }
        }
        map.put("services", services);
        return map;
    }

    @GetMapping("/context")
    Map<String, Object> context() {
        Map<String, Object> areas = tools.listAreas();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("area", tools.area());
        result.put("hub", tools.hub().enabled());
        result.put("areas", areas.get("areas"));
        result.put("links", links);
        return result;
    }

    private static Set<String> referencedServices(Map<String, Object> inbound) {
        Set<String> names = new LinkedHashSet<>();
        for (String field : List.of("calls", "observedCalls")) {
            for (Object item : (List<?>) inbound.get(field)) {
                names.add((String) cast(item).get("source"));
                names.add((String) cast(item).get("target"));
            }
        }
        for (String field : List.of("publishes", "consumes")) {
            for (Object item : (List<?>) inbound.get(field)) {
                names.add((String) cast(item).get("service"));
            }
        }
        return names;
    }

    private void describeExternal(Map<String, Object> service, Map<String, Map<String, Object>> catalog) {
        Map<String, Object> known = catalog.get((String) service.get("name"));
        if (known == null) {
            return;
        }
        if (service.get("area") == null) {
            service.put("area", known.get("area"));
            service.put("team", known.get("team"));
        }
        if (!Boolean.TRUE.equals(service.get("indexed")) && Boolean.TRUE.equals(known.get("indexed"))) {
            service.put("external", true);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<Object> concat(Object a, Object b) {
        List<Object> result = new ArrayList<>((List<?>) a);
        result.addAll((List<?>) b);
        return result;
    }

    @GetMapping("/issues")
    Map<String, Object> issues() {
        return tools.findContractIssues();
    }

    @GetMapping("/services/{name}")
    Map<String, Object> service(@PathVariable String name) {
        return tools.serviceOverview(name);
    }

    @GetMapping("/topics/{name}")
    Map<String, Object> topic(@PathVariable String name) {
        return tools.whoConsumes(name);
    }

    @GetMapping("/impact")
    Map<String, Object> impact(@RequestParam String service, @RequestParam String contract) {
        return tools.impactOfChange(service, contract);
    }
}
