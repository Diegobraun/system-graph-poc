package com.example.graph.extractor.ingest;

import com.example.graph.extractor.model.ExposedEndpoint;
import com.example.graph.extractor.model.HttpCall;
import com.example.graph.extractor.model.PayloadSchema;
import com.example.graph.extractor.model.Publication;
import com.example.graph.extractor.model.SchemaField;
import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.model.Subscription;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;

public final class GraphIngestor implements AutoCloseable {

    private static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT service_name IF NOT EXISTS FOR (n:Service) REQUIRE n.name IS UNIQUE",
            "CREATE CONSTRAINT endpoint_key IF NOT EXISTS FOR (n:Endpoint) REQUIRE n.key IS UNIQUE",
            "CREATE CONSTRAINT topic_name IF NOT EXISTS FOR (n:Topic) REQUIRE n.name IS UNIQUE",
            "CREATE CONSTRAINT schema_key IF NOT EXISTS FOR (n:Schema) REQUIRE n.key IS UNIQUE",
            "CREATE CONSTRAINT note_id IF NOT EXISTS FOR (n:Note) REQUIRE n.id IS UNIQUE");

    private final Driver driver;

    public GraphIngestor(Neo4jSettings settings) {
        this.driver = GraphDatabase.driver(settings.uri(), AuthTokens.basic(settings.user(), settings.password()));
        this.driver.verifyConnectivity();
    }

    public void ensureConstraints() {
        try (Session session = driver.session()) {
            CONSTRAINTS.forEach(statement -> session.run(statement).consume());
        }
    }

    public void ingest(ServiceGraph graph) {
        try (Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                Map<String, Object> base = Map.of("service", graph.service());
                upsertService(tx, graph);
                tx.run("MATCH (s:Service {name: $service})-[r:EXPOSES|CALLS|DEPENDS_ON|PUBLISHES|CONSUMES]->() DELETE r", base);
                tx.run("MATCH (s:Service {name: $service})-[:DEFINES]->(sc:Schema) DETACH DELETE sc", base);
                exposes(tx, graph);
                calls(tx, graph);
                publishes(tx, graph);
                consumes(tx, graph);
                schemas(tx, graph);
            });
            session.executeWriteWithoutResult(this::removeOrphans);
        }
    }

    private void upsertService(TransactionContext tx, ServiceGraph graph) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("service", graph.service());
        parameters.put("repository", graph.repository());
        parameters.put("graphqlSchema", graph.graphqlSchema());
        parameters.put("commitSha", graph.commitSha());
        parameters.put("extractedAt", graph.extractedAt());
        tx.run("""
                MERGE (s:Service {name: $service})
                SET s.repository = $repository,
                    s.graphqlSchema = $graphqlSchema,
                    s.commitSha = $commitSha,
                    s.extractedAt = $extractedAt,
                    s.ingestedAt = datetime(),
                    s.indexed = true
                """, parameters);
    }

    private void exposes(TransactionContext tx, ServiceGraph graph) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExposedEndpoint endpoint : graph.exposes()) {
            rows.add(Map.of(
                    "key", EndpointKey.of(graph.service(), endpoint.method(), endpoint.path()),
                    "method", endpoint.method(),
                    "path", endpoint.path(),
                    "handler", endpoint.handler(),
                    "protocol", protocol(endpoint.method())));
        }
        tx.run("""
                MATCH (s:Service {name: $service})
                UNWIND $rows AS row
                MERGE (e:Endpoint {key: row.key})
                SET e.service = $service, e.method = row.method, e.path = row.path, e.protocol = row.protocol
                MERGE (s)-[r:EXPOSES]->(e)
                SET r.handler = row.handler
                """, Map.of("service", graph.service(), "rows", rows));
    }

    private void calls(TransactionContext tx, ServiceGraph graph) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HttpCall call : graph.calls()) {
            if (call.targetService() == null || call.targetService().equals("unknown")) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("target", call.targetService());
            row.put("method", call.method());
            row.put("path", call.path());
            row.put("key", call.path() == null ? null : EndpointKey.of(call.targetService(), call.method(), call.path()));
            row.put("source", call.source());
            row.put("confidence", call.confidence());
            row.put("location", call.location());
            row.put("baseUrl", call.baseUrl());
            row.put("via", call.via());
            row.put("document", call.document());
            row.put("protocol", protocol(call.method()));
            rows.add(row);
        }
        tx.run("""
                MATCH (s:Service {name: $service})
                UNWIND $rows AS row
                MERGE (t:Service {name: row.target})
                ON CREATE SET t.indexed = false
                MERGE (s)-[d:DEPENDS_ON]->(t)
                SET d.source = row.source
                WITH s, row
                WHERE row.key IS NOT NULL
                MERGE (e:Endpoint {key: row.key})
                ON CREATE SET e.service = row.target, e.method = row.method, e.path = row.path, e.protocol = row.protocol
                MERGE (s)-[c:CALLS]->(e)
                SET c.source = row.source,
                    c.confidence = row.confidence,
                    c.location = row.location,
                    c.baseUrl = row.baseUrl,
                    c.via = row.via,
                    c.document = row.document
                """, Map.of("service", graph.service(), "rows", rows));
    }

    private void publishes(TransactionContext tx, ServiceGraph graph) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Publication publication : graph.publishes()) {
            Map<String, Object> row = new HashMap<>();
            row.put("topic", publication.topic());
            row.put("via", publication.via());
            row.put("payloadType", publication.payloadType());
            row.put("source", publication.source());
            row.put("location", publication.location());
            rows.add(row);
        }
        tx.run("""
                MATCH (s:Service {name: $service})
                UNWIND $rows AS row
                MERGE (t:Topic {name: row.topic})
                MERGE (s)-[p:PUBLISHES {via: row.via}]->(t)
                SET p.payloadType = row.payloadType,
                    p.source = row.source,
                    p.location = row.location
                """, Map.of("service", graph.service(), "rows", rows));
    }

    private void consumes(TransactionContext tx, ServiceGraph graph) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Subscription subscription : graph.consumes()) {
            Map<String, Object> row = new HashMap<>();
            row.put("topic", subscription.topic());
            row.put("via", subscription.via());
            row.put("group", subscription.group());
            row.put("payloadType", subscription.payloadType());
            row.put("source", subscription.source());
            row.put("location", subscription.location());
            rows.add(row);
        }
        tx.run("""
                MATCH (s:Service {name: $service})
                UNWIND $rows AS row
                MERGE (t:Topic {name: row.topic})
                MERGE (s)-[c:CONSUMES {via: row.via}]->(t)
                SET c.group = row.group,
                    c.payloadType = row.payloadType,
                    c.source = row.source,
                    c.location = row.location
                """, Map.of("service", graph.service(), "rows", rows));
    }

    private void schemas(TransactionContext tx, ServiceGraph graph) {
        Map<String, PayloadSchema> byClass = new HashMap<>();
        graph.schemas().forEach(schema -> byClass.put(schema.className(), schema));
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Publication publication : graph.publishes()) {
            schemaRow(graph.service(), publication.topic(), "producer", byClass.get(publication.payloadType()))
                    .ifPresent(row -> rows.put((String) row.get("key"), row));
        }
        for (Subscription subscription : graph.consumes()) {
            schemaRow(graph.service(), subscription.topic(), "consumer", byClass.get(subscription.payloadType()))
                    .ifPresent(row -> rows.put((String) row.get("key"), row));
        }
        tx.run("""
                MATCH (s:Service {name: $service})
                UNWIND $rows AS row
                MATCH (t:Topic {name: row.topic})
                MERGE (sc:Schema {key: row.key})
                SET sc.service = $service,
                    sc.topic = row.topic,
                    sc.side = row.side,
                    sc.className = row.className,
                    sc.fieldNames = row.fieldNames,
                    sc.fieldTypes = row.fieldTypes
                MERGE (s)-[:DEFINES]->(sc)
                MERGE (t)-[:HAS_SCHEMA]->(sc)
                """, Map.of("service", graph.service(), "rows", new ArrayList<>(rows.values())));
    }

    private Optional<Map<String, Object>> schemaRow(String service, String topic, String side, PayloadSchema schema) {
        if (schema == null) {
            return Optional.empty();
        }
        return Optional.of(Map.of(
                "key", service + "|" + topic + "|" + side + "|" + schema.className(),
                "topic", topic,
                "side", side,
                "className", schema.className(),
                "fieldNames", schema.fields().stream().map(SchemaField::name).toList(),
                "fieldTypes", schema.fields().stream().map(SchemaField::type).toList()));
    }

    private static String protocol(String method) {
        return switch (method) {
            case "QUERY", "MUTATION", "SUBSCRIPTION", "GRAPHQL" -> "graphql";
            default -> "http";
        };
    }

    private void removeOrphans(TransactionContext tx) {
        tx.run("MATCH (e:Endpoint) WHERE NOT (e)--() DELETE e");
        tx.run("MATCH (t:Topic) WHERE NOT (t)--() DELETE t");
        tx.run("MATCH (s:Service) WHERE s.indexed = false AND NOT (s)--() DELETE s");
    }

    Driver driver() {
        return driver;
    }

    @Override
    public void close() {
        driver.close();
    }
}
