package com.example.graph.extractor.ingest;

import java.util.Map;

public record Neo4jSettings(String uri, String user, String password) {

    public static Neo4jSettings from(Map<String, String> options) {
        return new Neo4jSettings(
                option(options, "neo4j-uri", "NEO4J_URI", "bolt://localhost:7687"),
                option(options, "neo4j-user", "NEO4J_USER", "neo4j"),
                option(options, "neo4j-password", "NEO4J_PASSWORD", "password123"));
    }

    private static String option(Map<String, String> options, String name, String env, String fallback) {
        String value = options.get(name);
        if (value == null) {
            value = System.getenv(env);
        }
        return value == null ? fallback : value;
    }
}
