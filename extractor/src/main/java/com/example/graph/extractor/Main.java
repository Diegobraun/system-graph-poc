package com.example.graph.extractor;

import com.example.graph.extractor.crawl.CrawlConfig;
import com.example.graph.extractor.crawl.Crawler;
import com.example.graph.extractor.experimental.ExperimentalCommands;
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
import java.util.Set;

public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<String> FLAGS = Set.of("no-ingest", "no-pull", "no-build", "dry-run");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        boolean experimental = args[0].equals("experimental");
        if (experimental && args.length < 2) {
            System.err.println(ExperimentalCommands.usage());
            System.exit(1);
        }
        Map<String, String> options = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = experimental ? 2 : 1; i < args.length; i++) {
            if (args[i].startsWith("--") && FLAGS.contains(args[i].substring(2))) {
                options.put(args[i].substring(2), "true");
            } else if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i].substring(2), args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        switch (args[0]) {
            case "extract" -> extract(options);
            case "ingest" -> ingest(options, positional);
            case "kafka-runtime" -> kafkaRuntime(options);
            case "crawl" -> crawl(options);
            case "experimental" -> ExperimentalCommands.run(args[1], options);
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
        List<ServiceGraph> graphs = new ArrayList<>();
        for (String file : files) {
            graphs.add(JSON.readValue(Path.of(file).toFile(), ServiceGraph.class));
        }
        ingestGraphs(options, graphs);
    }

    private static void ingestGraphs(Map<String, String> options, List<ServiceGraph> graphs) {
        try (GraphIngestor ingestor = new GraphIngestor(Neo4jSettings.from(options))) {
            ingestor.ensureConstraints();
            for (ServiceGraph graph : graphs) {
                ingestor.ingest(graph);
                System.out.printf("ingested %s (%s)%n", graph.service(), graph.commitSha());
            }
        }
    }

    private static void crawl(Map<String, String> options) throws Exception {
        CrawlConfig config = CrawlConfig.load(Path.of(options.getOrDefault("config", "crawl.yml")));
        config = config.withOverrides(options.containsKey("no-pull") ? Boolean.FALSE : null, options.containsKey("no-build") ? "skip" : null);
        if (options.containsKey("only")) {
            config = config.only(List.of(options.get("only").split(",")));
        }
        if (options.containsKey("no-ingest")) {
            config = config.withIngest(false);
        }
        List<Crawler.ProjectResult> results = new Crawler(new Extractor(), System.out).crawl(config);
        List<ServiceGraph> graphs = results.stream().filter(Crawler.ProjectResult::ok).map(Crawler.ProjectResult::graph).toList();
        if (config.ingest() && !graphs.isEmpty()) {
            System.out.println();
            ingestGraphs(options, graphs);
            if (config.kafkaBootstrap() != null) {
                options.putIfAbsent("bootstrap", config.kafkaBootstrap());
                kafkaRuntime(options);
            }
        }
        if (results.stream().anyMatch(r -> !r.ok())) {
            System.exit(2);
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
                  graph-extractor crawl --config crawl.yml [--only a,b] [--no-pull] [--no-build] [--no-ingest] [neo4j options]
                  graph-extractor experimental <command> ...   (see graph-extractor experimental)
                """);
    }
}
