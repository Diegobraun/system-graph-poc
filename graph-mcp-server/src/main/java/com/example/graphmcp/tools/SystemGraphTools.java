package com.example.graphmcp.tools;

import com.example.graphmcp.graph.EndpointKey;
import com.example.graphmcp.graph.GraphClient;
import com.example.graphmcp.graph.GraphQlAnalyzer;
import com.example.graphmcp.graph.SchemaComparator;
import com.example.graphmcp.graph.SchemaComparator.SchemaIssue;
import com.example.graphmcp.graph.SchemaComparator.SchemaView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SystemGraphTools {

    private static final String NOTES = "[(n:Note)-[:ABOUT]->(%s) | n {.id, .text, .author, .status, .createdAt}]";
    private static final Map<String, String[]> NOTE_TARGETS = Map.of(
            "service", new String[]{"Service", "name"},
            "topic", new String[]{"Topic", "name"},
            "endpoint", new String[]{"Endpoint", "key"});

    private static final Pattern GRAPHQL_FIELD = Pattern.compile("^[A-Z][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*$");

    private final GraphClient graph;

    public SystemGraphTools(GraphClient graph) {
        this.graph = graph;
    }

    @Tool(name = "list_services", description = """
            Lists every service known in the system graph, with its repository, the commit it was extracted from and how many \
            endpoints it exposes. Services with indexed=false are referenced by others but were never extracted.""")
    public List<Map<String, Object>> listServices() {
        return graph.read("""
                MATCH (s:Service)
                RETURN s.name AS service,
                       coalesce(s.indexed, false) AS indexed,
                       s.repository AS repository,
                       s.commitSha AS commitSha,
                       s.extractedAt AS extractedAt,
                       s.graphqlSchema IS NOT NULL AS graphql,
                       COUNT { (s)-[:EXPOSES]->() } AS endpoints,
                       COUNT { (s)-[:PUBLISHES]->() } AS publishes,
                       COUNT { (s)-[:CONSUMES]->() } AS consumes
                ORDER BY service
                """, Map.of());
    }

    @Tool(name = "service_overview", description = """
            Full picture of one service: REST endpoints and GraphQL operations it exposes (and who calls each one), \
            HTTP and GraphQL calls it makes, \
            Kafka topics it publishes and consumes (with the other side of each topic), services that depend on it, \
            and team notes about it. Use it to understand a service before working on it.""")
    public Map<String, Object> serviceOverview(@ToolParam(description = "Service name, same as spring.application.name, e.g. account-service") String service) {
        List<Map<String, Object>> rows = graph.read("""
                MATCH (s:Service {name: $service})
                RETURN s.name AS service,
                       coalesce(s.indexed, false) AS indexed,
                       s.repository AS repository,
                       s.commitSha AS commitSha,
                       s.extractedAt AS extractedAt,
                       [(s)-[r:EXPOSES]->(e) | {protocol: e.protocol, method: e.method, path: e.path, handler: r.handler,
                           calledBy: [(c:Service)-[:CALLS]->(e) | c.name]}] AS exposes,
                       [(s)-[r:CALLS]->(e) | {service: e.service, protocol: e.protocol, via: r.via, method: e.method, path: e.path,
                           location: r.location, confidence: r.confidence}] AS calls,
                       [(s)-[r:PUBLISHES]->(t) | {topic: t.name, via: r.via, payloadType: r.payloadType,
                           consumers: [(c:Service)-[:CONSUMES]->(t) | c.name]}] AS publishes,
                       [(s)-[r:CONSUMES]->(t) | {topic: t.name, via: r.via, group: r.group, payloadType: r.payloadType,
                           producers: [(p:Service)-[:PUBLISHES]->(t) | p.name]}] AS consumes,
                       s.graphqlSchema AS graphqlSchema,
                       [(d:Service)-[:DEPENDS_ON]->(s) | d.name] AS dependents,
                       [(s)-[:DEPENDS_ON]->(d:Service) | d.name] AS dependsOn,
                       %s AS notes
                """.formatted(NOTES.formatted("s")), Map.of("service", service));
        if (rows.isEmpty()) {
            return notFound("service", service);
        }
        return rows.getFirst();
    }

    @Tool(name = "who_consumes", description = """
            For a Kafka topic, lists producers, consumers declared in code, and consumer groups actually observed \
            in the Kafka cluster at runtime. A runtime consumer missing from the declared list is a hidden dependency.""")
    public Map<String, Object> whoConsumes(@ToolParam(description = "Kafka topic name, e.g. account-opened") String topic) {
        List<Map<String, Object>> rows = graph.read("""
                MATCH (t:Topic {name: $topic})
                RETURN t.name AS topic,
                       [(p:Service)-[r:PUBLISHES]->(t) | {service: p.name, via: r.via, payloadType: r.payloadType, location: r.location}] AS producers,
                       [(c:Service)-[r:CONSUMES]->(t) | {service: c.name, repository: c.repository, via: r.via, group: r.group, payloadType: r.payloadType, location: r.location}] AS declaredConsumers,
                       [(c:Service)-[r:OBSERVED_CONSUMING]->(t) | {service: c.name, activeMembers: r.activeMembers, state: r.state, observedAt: r.observedAt}] AS observedConsumers,
                       %s AS notes
                """.formatted(NOTES.formatted("t")), Map.of("topic", topic));
        if (rows.isEmpty()) {
            return notFound("topic", topic);
        }
        return rows.getFirst();
    }

    @Tool(name = "impact_of_change", description = """
            Call this BEFORE changing a REST endpoint, a GraphQL operation or type field, or a Kafka event of a service. \
            Returns which other services break or need changes, with their repository and file location (they usually live \
            in other repositories you cannot see), payload problems between producer and consumers, and team notes. \
            The contract is a Kafka topic ('account-opened'), an HTTP endpoint ('GET /accounts/{id}'), \
            a GraphQL root operation ('QUERY customer') or a GraphQL type field ('Customer.monthlyIncome').""")
    public Map<String, Object> impactOfChange(
            @ToolParam(description = "Service that owns the contract, e.g. account-service") String service,
            @ToolParam(description = "Kafka topic (account-opened), endpoint (GET /accounts/{id} or /accounts/{id}), GraphQL operation (QUERY customer) or GraphQL field (Customer.monthlyIncome)") String contract) {
        List<Map<String, Object>> topicRelations = graph.read("""
                MATCH (:Service {name: $service})-[r:PUBLISHES|CONSUMES]->(t:Topic {name: $contract})
                RETURN collect(DISTINCT type(r)) AS relations
                """, Map.of("service", service, "contract", contract));
        @SuppressWarnings("unchecked")
        List<String> relations = (List<String>) topicRelations.getFirst().get("relations");
        if (!relations.isEmpty()) {
            return topicImpact(service, contract, relations);
        }
        if (GRAPHQL_FIELD.matcher(contract.trim()).matches()) {
            return graphQlFieldImpact(service, contract.trim());
        }
        return endpointImpact(service, contract);
    }

    @Tool(name = "compare_event_schemas", description = """
            Compares the payload classes used by producers and consumers of a Kafka topic, field by field. \
            There is no schema registry, so this is the only check that producer and consumer agree on the event format.""")
    public Map<String, Object> compareEventSchemas(@ToolParam(description = "Kafka topic name") String topic) {
        List<SchemaView> schemas = schemasOf(topic);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", topic);
        result.put("schemas", schemas);
        result.put("issues", SchemaComparator.compare(topic, schemas));
        return result;
    }

    @Tool(name = "find_contract_issues", description = """
            Scans the whole graph for integration problems: HTTP or GraphQL calls to operations nobody exposes, GraphQL client \
            documents that are invalid against the server schema, topics consumed with no producer, consumers seen in Kafka \
            but not declared in code, and producer/consumer payload mismatches.""")
    public Map<String, Object> findContractIssues() {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Map<String, Object> row : graph.read("""
                MATCH (c:Service)-[r:CALLS]->(e:Endpoint)
                WHERE NOT ()-[:EXPOSES]->(e)
                OPTIONAL MATCH (t:Service {name: e.service})
                RETURN c.name AS caller, c.repository AS repository, e.service AS target, e.method AS method, e.path AS path,
                       coalesce(e.protocol, 'http') AS protocol, coalesce(t.indexed, false) AS targetIndexed, r.location AS location
                """, Map.of())) {
            boolean indexed = (Boolean) row.get("targetIndexed");
            issues.add(issue(indexed ? "error" : "warning", (String) row.get("protocol"),
                    indexed
                            ? "%s calls %s %s on %s, but %s does not expose it".formatted(row.get("caller"), row.get("method"), row.get("path"), row.get("target"), row.get("target"))
                            : "%s calls %s, which is not indexed in the graph yet".formatted(row.get("caller"), row.get("target")),
                    row));
        }

        for (Map<String, Object> row : graphQlDocuments(null)) {
            GraphQlAnalyzer.Analysis analysis = GraphQlAnalyzer.analyze((String) row.get("sdl"), (String) row.get("document"));
            for (String error : analysis.errors()) {
                issues.add(issue("error", "graphql", "%s sends a GraphQL document that %s rejects: %s".formatted(row.get("caller"), row.get("target"), error),
                        Map.of("caller", row.get("caller"), "target", row.get("target"),
                                "repository", String.valueOf(row.get("repository")), "location", String.valueOf(row.get("location")))));
            }
        }

        List<Map<String, Object>> topics = graph.read("""
                MATCH (t:Topic)
                RETURN t.name AS topic,
                       [(p:Service)-[:PUBLISHES]->(t) | p.name] AS producers,
                       [(c:Service)-[:CONSUMES]->(t) | c.name] AS consumers,
                       [(o:Service)-[:OBSERVED_CONSUMING]->(t) | o.name] AS observed
                ORDER BY topic
                """, Map.of());
        boolean runtimeDataAvailable = topics.stream().anyMatch(t -> !((List<?>) t.get("observed")).isEmpty());
        for (Map<String, Object> row : topics) {
            String topic = (String) row.get("topic");
            Set<Object> producers = new HashSet<>((List<?>) row.get("producers"));
            Set<Object> consumers = new HashSet<>((List<?>) row.get("consumers"));
            Set<Object> observed = new HashSet<>((List<?>) row.get("observed"));
            if (producers.isEmpty() && !consumers.isEmpty()) {
                issues.add(issue("warning", "kafka", "topic %s is consumed by %s but no known service publishes it".formatted(topic, consumers), row));
            }
            if (!producers.isEmpty() && consumers.isEmpty() && observed.isEmpty()) {
                issues.add(issue("info", "kafka", "topic %s is published by %s but nobody consumes it".formatted(topic, producers), row));
            }
            for (Object service : observed) {
                if (!consumers.contains(service)) {
                    issues.add(issue("warning", "kafka", "consumer group %s reads %s in Kafka but the code of %s does not declare it".formatted(service, topic, service), row));
                }
            }
            if (runtimeDataAvailable) {
                for (Object service : consumers) {
                    if (!observed.contains(service)) {
                        issues.add(issue("info", "kafka", "%s declares a consumer for %s but it was not seen in Kafka (service down or dead code)".formatted(service, topic), row));
                    }
                }
            }
            for (SchemaIssue schemaIssue : SchemaComparator.compare(topic, schemasOf(topic))) {
                if (!"info".equals(schemaIssue.severity())) {
                    issues.add(issue(schemaIssue.severity(), "schema", schemaIssue.problem(), Map.of(
                            "topic", topic, "producer", String.valueOf(schemaIssue.producer()),
                            "consumer", String.valueOf(schemaIssue.consumer()), "field", String.valueOf(schemaIssue.field()))));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueCount", issues.size());
        result.put("issues", issues);
        return result;
    }

    @Tool(name = "record_note", description = """
            Saves something learned about a service, topic or endpoint (business rule, gotcha, decision) so other \
            people and AI sessions working on other repositories see it. Notes start with status 'pending' until a human reviews them. \
            Only record facts confirmed in code or by the user, never guesses.""")
    public Map<String, Object> recordNote(
            @ToolParam(description = "One of: service, topic, endpoint") String targetType,
            @ToolParam(description = "Service name, topic name, or endpoint as 'service METHOD /path', e.g. 'account-service GET /accounts/{id}'") String target,
            @ToolParam(description = "The note, one or two sentences") String text,
            @ToolParam(description = "Who is recording it, e.g. the developer name or 'claude-code'") String author) {
        String[] label = NOTE_TARGETS.get(targetType.toLowerCase(Locale.ROOT));
        if (label == null) {
            return Map.of("error", "targetType must be one of " + NOTE_TARGETS.keySet());
        }
        String key = "endpoint".equals(targetType.toLowerCase(Locale.ROOT)) ? endpointKey(target) : target;
        List<Map<String, Object>> rows = graph.write("""
                MATCH (x:%s {%s: $key})
                CREATE (n:Note {id: randomUUID(), text: $text, author: $author, status: 'pending', createdAt: datetime()})-[:ABOUT]->(x)
                RETURN n.id AS id, n.status AS status
                """.formatted(label[0], label[1]), Map.of("key", key, "text", text, "author", author));
        if (rows.isEmpty()) {
            return notFound(targetType, target);
        }
        return rows.getFirst();
    }

    @Tool(name = "graph_model", description = """
            Describes the node labels, relationships and properties of the system graph, with counts. \
            Read it before writing a query for read_cypher.""")
    public Map<String, Object> graphModel() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", Map.of(
                "Service", "name, indexed, repository, commitSha, extractedAt, graphqlSchema (SDL text, when the service exposes GraphQL)",
                "Endpoint", "key ('service METHOD /path/{}' for HTTP, 'service QUERY field' for GraphQL), service, method, path, protocol (http|graphql)",
                "Topic", "name",
                "Schema", "key, service, topic, side (producer|consumer), className, fieldNames[], fieldTypes[]",
                "Note", "id, text, author, status (pending|approved|rejected), createdAt"));
        result.put("relationships", List.of(
                "(Service)-[:EXPOSES {handler}]->(Endpoint)",
                "(Service)-[:CALLS {via (rest-client|web-client|feign|http-exchange|graphql|manifest), location, confidence, source, baseUrl, document}]->(Endpoint)",
                "(Service)-[:DEPENDS_ON {source}]->(Service)",
                "(Service)-[:PUBLISHES {via, payloadType, location, source}]->(Topic)",
                "(Service)-[:CONSUMES {via, group, payloadType, location, source}]->(Topic)",
                "(Service)-[:OBSERVED_CONSUMING {activeMembers, state, observedAt}]->(Topic)",
                "(Service)-[:DEFINES]->(Schema)<-[:HAS_SCHEMA]-(Topic)",
                "(Note)-[:ABOUT]->(Service|Topic|Endpoint)"));
        result.put("nodeCounts", graph.read("MATCH (n) RETURN labels(n)[0] AS label, count(*) AS count ORDER BY label", Map.of()));
        result.put("relationshipCounts", graph.read("MATCH ()-[r]->() RETURN type(r) AS type, count(*) AS count ORDER BY type", Map.of()));
        return result;
    }

    @Tool(name = "read_cypher", description = """
            Runs a read-only Cypher query against the system graph for questions the other tools do not answer. \
            Writes are rejected by the database. Results are capped at 200 rows. Call graph_model first.""")
    public Map<String, Object> readCypher(@ToolParam(description = "Cypher query, read only") String query) {
        try {
            List<Map<String, Object>> rows = graph.read(query, Map.of());
            return Map.of("rowCount", rows.size(), "rows", rows);
        } catch (Neo4jException e) {
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> topicImpact(String service, String topic, List<String> relations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("contract", topic);
        result.put("kind", "kafka-topic");
        result.put("role", relations.contains("PUBLISHES") ? "producer" : "consumer");
        Map<String, Object> consumers = whoConsumes(topic);
        List<SchemaView> schemas = schemasOf(topic);
        if (relations.contains("PUBLISHES")) {
            Map<String, Map<String, Object>> affected = new LinkedHashMap<>();
            for (Object item : (List<?>) consumers.get("declaredConsumers")) {
                Map<?, ?> consumer = (Map<?, ?>) item;
                affected.computeIfAbsent((String) consumer.get("service"), k -> new LinkedHashMap<>(Map.of("service", k)))
                        .putAll(Map.of("repository", String.valueOf(consumer.get("repository")),
                                "declaredIn", String.valueOf(consumer.get("location")),
                                "payloadType", String.valueOf(consumer.get("payloadType")),
                                "via", String.valueOf(consumer.get("via"))));
            }
            for (Object item : (List<?>) consumers.get("observedConsumers")) {
                Map<?, ?> consumer = (Map<?, ?>) item;
                affected.computeIfAbsent((String) consumer.get("service"), k -> new LinkedHashMap<>(Map.of("service", k)))
                        .put("seenInKafka", true);
            }
            result.put("affectedServices", affected.values());
            result.put("guidance", "Changing or removing fields of this event affects every service above. "
                    + "Adding optional fields is safe; renaming, removing or changing types is breaking.");
        } else {
            result.put("affectedServices", List.of());
            result.put("producers", consumers.get("producers"));
            result.put("guidance", service + " only consumes this topic. Changing its payload class affects only itself, "
                    + "but it must stay compatible with what the producers send.");
        }
        result.put("schemas", schemas);
        result.put("schemaIssues", SchemaComparator.compare(topic, schemas));
        result.put("notes", consumers.get("notes"));
        return result;
    }

    private Map<String, Object> endpointImpact(String service, String contract) {
        String trimmed = contract.trim();
        String method = null;
        String path = trimmed;
        int space = trimmed.indexOf(' ');
        if (space > 0 && !trimmed.startsWith("/")) {
            method = trimmed.substring(0, space).toUpperCase(Locale.ROOT);
            path = trimmed.substring(space + 1);
        }
        String normalized = EndpointKey.normalizePath(path);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("service", service);
        parameters.put("method", method);
        List<Map<String, Object>> endpoints = graph.read("""
                MATCH (:Service {name: $service})-[:EXPOSES]->(e:Endpoint)
                WHERE $method IS NULL OR e.method = $method
                RETURN e.key AS key, e.method AS method, e.path AS path
                """, parameters).stream()
                .filter(e -> EndpointKey.normalizePath((String) e.get("path")).equals(normalized))
                .toList();
        if (endpoints.isEmpty()) {
            Map<String, Object> missing = notFound("contract", contract);
            missing.put("hint", "Use service_overview to see the endpoints and topics of " + service);
            return missing;
        }
        List<Map<String, Object>> impacts = new ArrayList<>();
        for (Map<String, Object> endpoint : endpoints) {
            Map<String, Object> impact = new LinkedHashMap<>(endpoint);
            List<Map<String, Object>> callers = new ArrayList<>();
            for (Map<String, Object> caller : graph.read("""
                    MATCH (c:Service)-[r:CALLS]->(e:Endpoint {key: $key})
                    MATCH (owner:Service {name: e.service})
                    RETURN c.name AS service, c.repository AS repository, c.commitSha AS commitSha, r.via AS via,
                           r.location AS location, r.confidence AS confidence, r.source AS source,
                           r.document AS document, owner.graphqlSchema AS sdl
                    """, Map.of("key", endpoint.get("key")))) {
                Map<String, Object> entry = new LinkedHashMap<>(caller);
                Object document = entry.remove("document");
                Object sdl = entry.remove("sdl");
                if (document != null && sdl != null) {
                    GraphQlAnalyzer.Analysis analysis = GraphQlAnalyzer.analyze((String) sdl, (String) document);
                    entry.put("fieldsUsed", analysis.fieldsUsed());
                    entry.put("documentErrors", analysis.errors());
                }
                callers.add(entry);
            }
            impact.put("callers", callers);
            impact.put("notes", graph.read("MATCH (e:Endpoint {key: $key}) RETURN " + NOTES.formatted("e") + " AS notes",
                    Map.of("key", endpoint.get("key"))).getFirst().get("notes"));
            impacts.add(impact);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("contract", contract);
        boolean graphql = endpoints.stream().allMatch(e -> Set.of("QUERY", "MUTATION", "SUBSCRIPTION").contains(e.get("method")));
        result.put("kind", graphql ? "graphql-operation" : "rest-endpoint");
        result.put("endpoints", impacts);
        result.put("guidance", graphql
                ? "Callers above select the fields listed in fieldsUsed. Removing or renaming any of them, or changing arguments, breaks that caller. "
                        + "Adding fields is safe."
                : "Callers above parse this endpoint's response. Renaming the path, changing the method, "
                        + "or removing/renaming response fields breaks them.");
        return result;
    }

    private Map<String, Object> graphQlFieldImpact(String service, String typeAndField) {
        List<Map<String, Object>> owner = graph.read("MATCH (s:Service {name: $service}) RETURN s.graphqlSchema AS sdl", Map.of("service", service));
        if (owner.isEmpty() || owner.getFirst().get("sdl") == null) {
            Map<String, Object> missing = notFound("GraphQL schema of service", service);
            missing.put("hint", "Only services with .graphqls files under src/main/resources/graphql have a GraphQL schema in the graph");
            return missing;
        }
        String sdl = (String) owner.getFirst().get("sdl");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("contract", typeAndField);
        result.put("kind", "graphql-field");
        if (!GraphQlAnalyzer.hasField(sdl, typeAndField)) {
            result.put("error", typeAndField + " does not exist in the schema of " + service);
            return result;
        }
        List<Map<String, Object>> affected = new ArrayList<>();
        List<Map<String, Object>> unaffected = new ArrayList<>();
        for (Map<String, Object> row : graphQlDocuments(service)) {
            GraphQlAnalyzer.Analysis analysis = GraphQlAnalyzer.analyze(sdl, (String) row.get("document"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("service", row.get("caller"));
            entry.put("repository", row.get("repository"));
            entry.put("location", row.get("location"));
            entry.put("operations", row.get("operations"));
            if (analysis.fieldsUsed().contains(typeAndField)) {
                affected.add(entry);
            } else {
                entry.put("reason", analysis.valid() ? "does not select " + typeAndField : "document is invalid: " + analysis.errors());
                unaffected.add(entry);
            }
        }
        result.put("affectedServices", affected);
        result.put("otherGraphQlClients", unaffected);
        result.put("notes", graph.read("""
                MATCH (e:Endpoint {service: $service, protocol: 'graphql'})
                RETURN [(n:Note)-[:ABOUT]->(e) | n {.id, .text, .author, .status, .createdAt}] AS notes
                """, Map.of("service", service)).stream().flatMap(r -> ((List<?>) r.get("notes")).stream()).toList());
        result.put("guidance", "Removing, renaming or changing the type of " + typeAndField
                + " breaks every service in affectedServices. Deprecate it with @deprecated first and remove after they migrate.");
        return result;
    }

    private List<Map<String, Object>> graphQlDocuments(String targetService) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("target", targetService);
        return graph.read("""
                MATCH (c:Service)-[r:CALLS]->(e:Endpoint {protocol: 'graphql'})
                MATCH (t:Service {name: e.service})
                WHERE ($target IS NULL OR t.name = $target) AND t.graphqlSchema IS NOT NULL AND r.document IS NOT NULL
                RETURN c.name AS caller, c.repository AS repository, t.name AS target, t.graphqlSchema AS sdl,
                       r.document AS document, r.location AS location,
                       collect(e.method + ' ' + e.path) AS operations
                """, parameters);
    }

    private List<SchemaView> schemasOf(String topic) {
        return graph.read("""
                MATCH (:Topic {name: $topic})-[:HAS_SCHEMA]->(sc:Schema)
                RETURN sc.service AS service, sc.side AS side, sc.className AS className,
                       sc.fieldNames AS fieldNames, sc.fieldTypes AS fieldTypes
                ORDER BY sc.side DESC, sc.service
                """, Map.of("topic", topic)).stream()
                .map(row -> new SchemaView(
                        (String) row.get("service"),
                        (String) row.get("side"),
                        (String) row.get("className"),
                        strings(row.get("fieldNames")),
                        strings(row.get("fieldTypes"))))
                .toList();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static String endpointKey(String target) {
        String[] parts = target.trim().split("\\s+", 3);
        if (parts.length < 3) {
            return target;
        }
        return parts[0] + " " + parts[1].toUpperCase(Locale.ROOT) + " " + EndpointKey.normalizePath(parts[2]);
    }

    private static Map<String, Object> issue(String severity, String area, String message, Map<String, ?> details) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("area", area);
        issue.put("message", message);
        issue.put("details", details);
        return issue;
    }

    private static Map<String, Object> notFound(String kind, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", kind + " '" + name + "' not found in the system graph");
        return result;
    }
}
