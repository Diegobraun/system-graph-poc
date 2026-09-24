package com.example.graph.extractor;

import com.example.graph.extractor.ingest.GraphIngestor;
import com.example.graph.extractor.ingest.Neo4jSettings;
import com.example.graph.extractor.model.HttpCall;
import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.runtime.KafkaRuntimeInspector;
import com.example.graph.extractor.scan.Extractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        Map<String, String> options = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i].substring(2), args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        switch (args[0]) {
            case "extract" -> extract(options);
            case "ingest" -> ingest(options, positional);
            case "kafka-runtime" -> kafkaRuntime(options);
            default -> {
                usage();
                System.exit(1);
            }
        }
    }

    private static void extract(Map<String, String> options) throws Exception {
        Path project = Path.of(options.getOrDefault("project", ".")).toAbsolutePath().normalize();
        Path out = Path.of(options.getOrDefault("out", project.resolve("target/service-graph.json").toString()));
        ServiceGraph graph = new Extractor().extract(project);
        Files.createDirectories(out.toAbsolutePath().getParent());
        JSON.writeValue(out.toFile(), graph);
        System.out.printf("%s: %d endpoints, %d calls, %d publishes, %d consumes, %d schemas -> %s%n",
                graph.service(), graph.exposes().size(), graph.calls().size(), graph.publishes().size(),
                graph.consumes().size(), graph.schemas().size(), out);
        for (HttpCall call : graph.calls()) {
            if ("low".equals(call.confidence())) {
                System.out.printf("  warning: unresolved call %s %s at %s (declare it in system-graph.yml)%n",
                        call.method(), call.path(), call.location());
            }
        }
    }

    private static void ingest(Map<String, String> options, List<String> files) throws Exception {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("ingest needs at least one service-graph.json");
        }
        try (GraphIngestor ingestor = new GraphIngestor(Neo4jSettings.from(options))) {
            ingestor.ensureConstraints();
            for (String file : files) {
                ServiceGraph graph = JSON.readValue(Path.of(file).toFile(), ServiceGraph.class);
                ingestor.ingest(graph);
                System.out.printf("ingested %s (%s)%n", graph.service(), graph.commitSha());
            }
        }
    }

    private static void kafkaRuntime(Map<String, String> options) throws Exception {
        String bootstrap = options.getOrDefault("bootstrap", System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092"));
        KafkaRuntimeInspector inspector = new KafkaRuntimeInspector();
        List<KafkaRuntimeInspector.Observation> observations = inspector.inspect(bootstrap);
        inspector.write(Neo4jSettings.from(options), observations);
        observations.forEach(o -> System.out.printf("%s -> %s (%d active members, %s)%n", o.group(), o.topic(), o.activeMembers(), o.state()));
    }

    private static void usage() {
        System.err.println("""
                usage:
                  graph-extractor extract --project <dir> [--out <file>]
                  graph-extractor ingest [--neo4j-uri uri] [--neo4j-user user] [--neo4j-password pwd] <service-graph.json>...
                  graph-extractor kafka-runtime [--bootstrap host:port] [--neo4j-uri uri] [--neo4j-user user] [--neo4j-password pwd]
                """);
    }
}
