package com.example.graph.extractor.experimental;

import com.example.graph.extractor.experimental.jar.JarProject;
import com.example.graph.extractor.experimental.observed.DynatraceClient;
import com.example.graph.extractor.experimental.observed.ObservedCall;
import com.example.graph.extractor.experimental.observed.ObservedCallsImporter;
import com.example.graph.extractor.experimental.openapi.OpenApiImporter;
import com.example.graph.extractor.experimental.source.SourceOnlyAnnotationSource;
import com.example.graph.extractor.ingest.Neo4jSettings;
import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.scan.Extractor;
import com.example.graph.extractor.scan.ProjectLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ExperimentalCommands {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ExperimentalCommands() {
    }

    public static void run(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "extract-source" -> extractSource(options);
            case "extract-jar" -> extractJar(options);
            case "import-openapi" -> importOpenApi(options);
            case "import-observed-calls" -> importObservedCalls(options);
            case "import-dynatrace" -> importDynatrace(options);
            default -> throw new IllegalArgumentException("unknown experimental command: " + command + "\n" + usage());
        }
    }

    public static String usage() {
        return """
                experimental commands (not used by crawl, see docs/experimental.md):
                  graph-extractor experimental extract-source --project <dir> [--out <file>]
                  graph-extractor experimental extract-jar --jar <app.jar> [--sources <dir|sources.jar>] [--libs "empresa-*.jar,*-client-*.jar"] [--out <file>]
                  graph-extractor experimental import-openapi --service <name> --spec <file|url> [neo4j options]
                  graph-extractor experimental import-observed-calls --file <calls.json> [--source apm] [--names <names.yml>] [--only-indexed] [neo4j options]
                  graph-extractor experimental import-dynatrace --url <https://tenant.live.dynatrace.com> [--token-env DT_API_TOKEN] [--names <names.yml>] [--dry-run] [neo4j options]
                """;
    }

    private static void extractSource(Map<String, String> options) throws Exception {
        Path project = Path.of(required(options, "project")).toAbsolutePath().normalize();
        ServiceGraph graph = new Extractor(new SourceOnlyAnnotationSource()).extract(ProjectLayout.detect(project));
        write(graph, Path.of(options.getOrDefault("out", project.resolve("service-graph.source-only.json").toString())));
    }

    private static void extractJar(Map<String, String> options) throws Exception {
        Path jar = Path.of(required(options, "jar")).toAbsolutePath();
        Path sources = options.containsKey("sources") ? Path.of(options.get("sources")) : null;
        List<String> libs = options.containsKey("libs")
                ? Arrays.stream(options.get("libs").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.of();
        Path work = Files.createTempDirectory("graph-jar-");
        JarProject.Unpacked unpacked = JarProject.unpack(jar, sources, libs, work);
        ServiceGraph extracted = new Extractor().extract(unpacked.layout());
        ServiceGraph graph = new ServiceGraph(
                extracted.service(),
                unpacked.repository(),
                unpacked.commit() != null ? unpacked.commit() : "jar:" + jar.getFileName(),
                extracted.extractedAt(),
                extracted.exposes(),
                extracted.calls(),
                extracted.publishes(),
                extracted.consumes(),
                extracted.schemas(),
                extracted.graphqlSchema());
        if (sources == null) {
            System.out.println("  note: no sources given, RestClient/WebClient chains, KafkaTemplate and StreamBridge sends "
                    + "and createClient base URLs are not visible");
        }
        if (!unpacked.includedLibraries().isEmpty()) {
            System.out.println("  scanned libraries: " + String.join(", ", unpacked.includedLibraries()));
        }
        write(graph, Path.of(options.getOrDefault("out", graph.service() + ".jar-graph.json")));
    }

    private static void importOpenApi(Map<String, String> options) throws Exception {
        String service = required(options, "service");
        String spec = required(options, "spec");
        List<OpenApiImporter.Operation> operations = OpenApiImporter.parse(OpenApiImporter.load(spec));
        OpenApiImporter.write(Neo4jSettings.from(options), service, spec, operations);
        System.out.printf("%s: %d operations imported from %s%n", service, operations.size(), spec);
    }

    private static void importObservedCalls(Map<String, String> options) throws Exception {
        List<ObservedCall> calls = ObservedCallsImporter.normalize(
                ObservedCallsImporter.readJson(Path.of(required(options, "file"))),
                ObservedCallsImporter.readNames(options.containsKey("names") ? Path.of(options.get("names")) : null));
        String source = options.getOrDefault("source", "apm");
        ObservedCallsImporter.write(Neo4jSettings.from(options), source, calls, options.containsKey("only-indexed"));
        calls.forEach(c -> System.out.printf("%s -> %s%n", c.from(), c.to()));
        System.out.printf("%d observed calls imported with source %s%n", calls.size(), source);
    }

    private static void importDynatrace(Map<String, String> options) throws Exception {
        String tokenVariable = options.getOrDefault("token-env", "DT_API_TOKEN");
        String token = System.getenv(tokenVariable);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("set the Dynatrace API token (scope entities.read) in $" + tokenVariable);
        }
        DynatraceClient client = new DynatraceClient(URI.create(required(options, "url")), token, HttpClient.newHttpClient());
        List<ObservedCall> calls = ObservedCallsImporter.normalize(client.serviceCalls(),
                ObservedCallsImporter.readNames(options.containsKey("names") ? Path.of(options.get("names")) : null));
        calls.forEach(c -> System.out.printf("%s -> %s%n", c.from(), c.to()));
        if (options.containsKey("dry-run")) {
            System.out.printf("%d service calls found, nothing written (dry run)%n", calls.size());
            return;
        }
        ObservedCallsImporter.write(Neo4jSettings.from(options), "dynatrace", calls, options.containsKey("only-indexed"));
        System.out.printf("%d service calls imported from Dynatrace%n", calls.size());
    }

    private static void write(ServiceGraph graph, Path out) throws Exception {
        JSON.writeValue(out.toFile(), graph);
        System.out.printf("%s: %d endpoints, %d calls, %d publishes, %d consumes -> %s%n", graph.service(),
                graph.exposes().size(), graph.calls().size(), graph.publishes().size(), graph.consumes().size(), out);
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is required\n" + usage());
        }
        return value;
    }
}
