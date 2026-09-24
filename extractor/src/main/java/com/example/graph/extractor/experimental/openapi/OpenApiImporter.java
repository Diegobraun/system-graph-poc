package com.example.graph.extractor.experimental.openapi;

import com.example.graph.extractor.ingest.EndpointKey;
import com.example.graph.extractor.ingest.Neo4jSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.yaml.snakeyaml.Yaml;

public final class OpenApiImporter {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch", "head", "options");

    public record Operation(String method, String path, String operationId) {
    }

    private OpenApiImporter() {
    }

    public static String load(String location) throws IOException, InterruptedException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(location)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET " + location + " returned " + response.statusCode());
            }
            return response.body();
        }
        return Files.readString(Path.of(location));
    }

    @SuppressWarnings("unchecked")
    public static List<Operation> parse(String content) throws IOException {
        Map<String, Object> spec = content.trim().startsWith("{")
                ? new ObjectMapper().readValue(content, Map.class)
                : new Yaml().load(content);
        String basePath = basePath(spec);
        List<Operation> operations = new ArrayList<>();
        Object paths = spec.get("paths");
        if (!(paths instanceof Map<?, ?> pathMap)) {
            return operations;
        }
        for (Map.Entry<?, ?> path : pathMap.entrySet()) {
            if (!(path.getValue() instanceof Map<?, ?> methods)) {
                continue;
            }
            for (Map.Entry<?, ?> method : methods.entrySet()) {
                String name = method.getKey().toString().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(name)) {
                    continue;
                }
                Object operationId = method.getValue() instanceof Map<?, ?> details ? details.get("operationId") : null;
                operations.add(new Operation(name.toUpperCase(Locale.ROOT), join(basePath, path.getKey().toString()),
                        operationId == null ? null : operationId.toString()));
            }
        }
        return operations;
    }

    public static void write(Neo4jSettings settings, String service, String specLocation, List<Operation> operations) {
        List<Map<String, Object>> rows = operations.stream()
                .map(o -> Map.<String, Object>of(
                        "key", EndpointKey.of(service, o.method(), o.path()),
                        "method", o.method(),
                        "path", o.path(),
                        "handler", "openapi:" + (o.operationId() == null ? o.method() + " " + o.path() : o.operationId())))
                .toList();
        try (Driver driver = GraphDatabase.driver(settings.uri(), AuthTokens.basic(settings.user(), settings.password()));
             Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("""
                        MERGE (s:Service {name: $service})
                        ON CREATE SET s.indexed = false
                        SET s.contractSource = 'openapi', s.openapiSpec = $spec, s.openapiImportedAt = datetime()
                        """, Map.of("service", service, "spec", specLocation));
                tx.run("MATCH (:Service {name: $service})-[r:EXPOSES {source: 'openapi'}]->() DELETE r", Map.of("service", service));
                tx.run("""
                        MATCH (s:Service {name: $service})
                        UNWIND $rows AS row
                        MERGE (e:Endpoint {key: row.key})
                        SET e.service = $service, e.method = row.method, e.path = row.path, e.protocol = 'http'
                        MERGE (s)-[r:EXPOSES]->(e)
                        SET r.source = 'openapi', r.handler = row.handler
                        """, Map.of("service", service, "rows", rows));
            });
        }
    }

    private static String basePath(Map<String, Object> spec) {
        if (spec.get("basePath") instanceof String swagger2) {
            return swagger2;
        }
        if (spec.get("servers") instanceof List<?> servers && !servers.isEmpty() && servers.getFirst() instanceof Map<?, ?> server
                && server.get("url") instanceof String url) {
            String path = url.contains("://") ? URI.create(url).getPath() : url;
            return path == null ? "" : path;
        }
        return "";
    }

    private static String join(String base, String path) {
        String joined = ("/" + base + "/" + path).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }
}
