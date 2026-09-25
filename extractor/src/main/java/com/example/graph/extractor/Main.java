package com.example.graph.extractor;

import com.example.graph.extractor.crawl.CrawlConfig;
import com.example.graph.extractor.crawl.Crawler;
import com.example.graph.extractor.experimental.ExperimentalCommands;
import com.example.graph.extractor.ingest.GraphIngestor;
import com.example.graph.extractor.ingest.HubExporter;
import com.example.graph.extractor.ingest.Neo4jSettings;
import com.example.graph.extractor.ingest.Placement;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<String> FLAGS = Set.of("no-ingest", "no-pull", "no-build", "dry-run", "only-indexed");

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
            case "kafka-runtime" -> kafkaRuntime(options, null);
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
        Map<ServiceGraph, String> graphs = new LinkedHashMap<>();
        for (String file : files) {
            graphs.put(JSON.readValue(Path.of(file).toFile(), ServiceGraph.class), options.get("team"));
        }
        ingestGraphs(options, options.get("area"), graphs);
        exportToHub(hubOptions(options), options.get("area"), graphs);
    }

    private static void ingestGraphs(Map<String, String> options, String area, Map<ServiceGraph, String> graphs) {
        try (GraphIngestor ingestor = new GraphIngestor(Neo4jSettings.from(options))) {
            ingestor.ensureConstraints();
            graphs.forEach((graph, team) -> {
                ingestor.ingest(graph, new Placement(area, team), call -> true);
                System.out.printf("ingested %s (%s)%s%n", graph.service(), graph.commitSha(), area == null ? "" : " in area " + area);
            });
        }
    }

    private static Set<String> exportToHub(Map<String, String> hub, String area, Map<ServiceGraph, String> graphs) {
        if (hub.isEmpty()) {
            return Set.of();
        }
        if (area == null) {
            throw new IllegalArgumentException("exporting to the hub needs an area (--area or area: in crawl.yml)");
        }
        try (GraphIngestor ingestor = new GraphIngestor(Neo4jSettings.from(hub))) {
            Set<String> services = new HubExporter(ingestor).export(area, graphs);
            System.out.printf("exported contracts and outbound calls of %s to the hub (%s)%n", area, String.join(", ", services));
            return services;
        }
    }

    private static Map<String, String> hubOptions(Map<String, String> options) {
        Map<String, String> hub = new HashMap<>();
        options.forEach((key, value) -> {
            if (key.startsWith("hub-")) {
                hub.put("neo4j-" + key.substring(4), value);
            }
        });
        return hub;
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
        config.neo4j().forEach(options::putIfAbsent);
        Map<String, String> hub = hubOptions(options);
        if (hub.isEmpty()) {
            hub.putAll(config.hub());
        }
        Map<String, String> teams = new HashMap<>();
        config.projects().forEach(p -> {
            if (p.team() != null) {
                teams.put(p.name(), p.team());
            }
        });
        List<Crawler.ProjectResult> results = new Crawler(new Extractor(), System.out).crawl(config);
        Map<ServiceGraph, String> graphs = new LinkedHashMap<>();
        results.stream().filter(Crawler.ProjectResult::ok).forEach(r -> graphs.put(r.graph(), teams.get(r.project())));
        if (config.ingest() && !graphs.isEmpty()) {
            System.out.println();
            ingestGraphs(options, config.area(), graphs);
            exportToHub(hub, config.area(), graphs);
            if (config.kafkaBootstrap() != null) {
                options.putIfAbsent("bootstrap", config.kafkaBootstrap());
                Set<String> services = new TreeSet<>();
                graphs.keySet().forEach(g -> services.add(g.service()));
                kafkaRuntime(options, config.area() == null ? null : services);
            }
        }
        if (results.stream().anyMatch(r -> !r.ok())) {
            System.exit(2);
        }
    }

    private static void kafkaRuntime(Map<String, String> options, Set<String> onlyServices) throws Exception {
        String bootstrap = options.getOrDefault("bootstrap", System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092"));
        KafkaRuntimeInspector inspector = new KafkaRuntimeInspector();
        List<KafkaRuntimeInspector.Observation> observations = inspector.inspect(bootstrap).stream()
                .filter(o -> onlyServices == null || onlyServices.contains(o.group()))
                .toList();
        inspector.write(Neo4jSettings.from(options), observations);
        observations.forEach(o -> System.out.printf("%s -> %s (%d active members, %s)%n", o.group(), o.topic(), o.activeMembers(), o.state()));
    }

    private static void usage() {
        System.err.println("""
                usage:
                  graph-extractor extract --project <dir> [--out <file>]
                  graph-extractor ingest [--area name --team name] [--hub-uri uri --hub-user user --hub-password pwd]
                                         [--neo4j-uri uri] [--neo4j-user user] [--neo4j-password pwd] <service-graph.json>...
                  graph-extractor kafka-runtime [--bootstrap host:port] [--neo4j-uri uri] [--neo4j-user user] [--neo4j-password pwd]
                  graph-extractor crawl --config crawl.yml [--only a,b] [--no-pull] [--no-build] [--no-ingest] [neo4j options]
                  graph-extractor experimental <command> ...   (see graph-extractor experimental)
                """);
    }
}
