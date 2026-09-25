package com.example.graphmcp.graph;

import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.types.IsoDuration;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Point;
import org.neo4j.driver.types.Relationship;

public class GraphClient {

    private final Driver driver;
    private final TransactionConfig readConfig;
    private final int maxRows;

    public GraphClient(Driver driver, Duration timeout, int maxRows) {
        this.driver = driver;
        this.readConfig = TransactionConfig.builder().withTimeout(timeout).build();
        this.maxRows = maxRows;
    }

    public List<Map<String, Object>> read(String query, Map<String, Object> parameters) {
        try (Session session = driver.session(SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build())) {
            return session.executeRead(tx -> tx.run(query, parameters).stream()
                    .limit(maxRows)
                    .map(record -> convertMap(record.asMap()))
                    .toList(), readConfig);
        }
    }

    public List<Map<String, Object>> write(String query, Map<String, Object> parameters) {
        try (Session session = driver.session(SessionConfig.builder().withDefaultAccessMode(AccessMode.WRITE).build())) {
            return session.executeWrite(tx -> tx.run(query, parameters).stream()
                    .map(record -> convertMap(record.asMap()))
                    .toList());
        }
    }

    private static Map<String, Object> convertMap(Map<String, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(key, convert(value)));
        return result;
    }

    private static Object convert(Object value) {
        return switch (value) {
            case null -> null;
            case Node node -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("labels", node.labels());
                result.putAll(convertMap(node.asMap()));
                yield result;
            }
            case Relationship relationship -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", relationship.type());
                result.putAll(convertMap(relationship.asMap()));
                yield result;
            }
            case Path path -> path.toString();
            case Map<?, ?> map -> {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), convert(v)));
                yield result;
            }
            case List<?> list -> list.stream().map(GraphClient::convert).toList();
            case TemporalAccessor temporal -> temporal.toString();
            case IsoDuration duration -> duration.toString();
            case Point point -> point.toString();
            default -> value;
        };
    }
}
