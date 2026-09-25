package com.example.graph.extractor.experimental.observed;

import com.example.graph.extractor.ingest.Neo4jSettings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.yaml.snakeyaml.Yaml;

public final class ObservedCallsImporter {

    private ObservedCallsImporter() {
    }

    public static List<ObservedCall> readJson(Path file) throws IOException {
        return new ObjectMapper().readValue(file.toFile(), new TypeReference<List<ObservedCall>>() {
        });
    }

    public static Map<String, String> readNames(Path file) throws IOException {
        if (file == null) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> loaded = new Yaml().load(reader);
            Map<String, String> names = new HashMap<>();
            if (loaded != null) {
                loaded.forEach((k, v) -> names.put(k, String.valueOf(v)));
            }
            return names;
        }
    }

    public static List<ObservedCall> normalize(List<ObservedCall> calls, Map<String, String> names) {
        Map<String, ObservedCall> merged = new LinkedHashMap<>();
        for (ObservedCall call : calls) {
            String from = name(call.from(), names);
            String to = name(call.to(), names);
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            merged.merge(from + "->" + to, new ObservedCall(from, to, call.count()),
                    (a, b) -> new ObservedCall(from, to, a.count() == null || b.count() == null ? null : a.count() + b.count()));
        }
        return List.copyOf(merged.values());
    }

    public static void write(Neo4jSettings settings, String source, List<ObservedCall> calls) {
        write(settings, source, calls, false);
    }

    public static void write(Neo4jSettings settings, String source, List<ObservedCall> calls, boolean onlyIndexed) {
        List<Map<String, Object>> rows = calls.stream().map(call -> {
            Map<String, Object> row = new HashMap<>();
            row.put("from", call.from());
            row.put("to", call.to());
            row.put("count", call.count());
            return row;
        }).toList();
        try (Driver driver = GraphDatabase.driver(settings.uri(), AuthTokens.basic(settings.user(), settings.password()));
             Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("MATCH ()-[r:OBSERVED_CALLS {source: $source}]->() DELETE r", Map.of("source", source));
                tx.run("""
                        UNWIND $rows AS row
                        WITH row
                        WHERE NOT $onlyIndexed OR EXISTS { MATCH (s:Service {indexed: true}) WHERE s.name IN [row.from, row.to] }
                        MERGE (a:Service {name: row.from})
                        ON CREATE SET a.indexed = false
                        MERGE (b:Service {name: row.to})
                        ON CREATE SET b.indexed = false
                        MERGE (a)-[r:OBSERVED_CALLS {source: $source}]->(b)
                        SET r.count = row.count, r.observedAt = datetime()
                        """, Map.of("source", source, "rows", rows, "onlyIndexed", onlyIndexed));
            });
        }
    }

    private static String name(String raw, Map<String, String> names) {
        if (raw == null) {
            return null;
        }
        String mapped = names.get(raw);
        if (mapped != null) {
            return mapped.isBlank() ? null : mapped;
        }
        return Objects.requireNonNull(raw).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }
}
