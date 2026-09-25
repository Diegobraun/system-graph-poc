package com.example.graphmcp.graph;

import com.example.graphmcp.tools.SystemGraphTools;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;

public final class Hub implements AutoCloseable {

    private static final Hub NONE = new Hub(null, null, null);

    private final Driver driver;
    private final GraphClient graph;
    private final SystemGraphTools tools;

    private Hub(Driver driver, GraphClient graph, SystemGraphTools tools) {
        this.driver = driver;
        this.graph = graph;
        this.tools = tools;
    }

    public static Hub none() {
        return NONE;
    }

    public static Hub of(Driver driver, GraphClient graph) {
        return new Hub(driver, graph, new SystemGraphTools(graph, NONE, null));
    }

    public boolean enabled() {
        return graph != null;
    }

    public GraphClient graph() {
        return graph;
    }

    public SystemGraphTools tools() {
        return tools;
    }

    public List<Map<String, Object>> catalog() {
        return graph.read("MATCH (s:Service) RETURN s.name AS name, s.area AS area, s.team AS team, coalesce(s.indexed, false) AS indexed", Map.of());
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }
}
