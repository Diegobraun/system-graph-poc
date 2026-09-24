package com.example.graphmcp.web;

import com.example.graphmcp.graph.GraphClient;
import com.example.graphmcp.tools.SystemGraphTools;
import java.util.Map;
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
                RETURN collect(s {.name, .repository, .commitSha, .contractSource,
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

    private final GraphClient graph;
    private final SystemGraphTools tools;

    GraphViewController(GraphClient graph, SystemGraphTools tools) {
        this.graph = graph;
        this.tools = tools;
    }

    @GetMapping("/map")
    Map<String, Object> map() {
        return graph.read(MAP, Map.of()).getFirst();
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
