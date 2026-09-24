package com.example.graph.extractor.scan;

import com.example.graph.extractor.model.ExposedEndpoint;
import com.example.graph.extractor.model.HttpCall;
import com.example.graph.extractor.model.PayloadSchema;
import com.example.graph.extractor.model.Publication;
import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.model.Subscription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class Extractor {

    private static final String BINDINGS = "spring.cloud.stream.bindings.";

    public ServiceGraph extract(Path projectDir) throws IOException {
        Path classes = projectDir.resolve("target/classes");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException("Compiled classes not found at " + classes + ". Run mvn compile first.");
        }
        Path resources = projectDir.resolve("src/main/resources");
        SpringProperties properties = SpringProperties.load(resources);
        Manifest manifest = Manifest.load(projectDir);
        String service = properties.get("spring.application.name")
                .or(() -> Optional.ofNullable(manifest.service()))
                .orElse(projectDir.getFileName().toString());

        AnnotationScanner.Result annotations = new AnnotationScanner(properties).scan(classes);
        SourceScanner.Result sources = new SourceScanner(projectDir, properties, annotations.classIndex()).scan();

        Optional<GraphQlScanner.Schema> graphqlSchema = GraphQlScanner.loadSchema(resources);
        List<ExposedEndpoint> exposes = new ArrayList<>(annotations.exposes());
        graphqlSchema.ifPresent(schema -> schema.operations().forEach(operation -> exposes.add(new ExposedEndpoint(
                operation.type(), operation.field(),
                annotations.graphqlHandlers().getOrDefault(operation.type() + " " + operation.field(), "graphql schema")))));

        List<HttpCall> calls = new ArrayList<>(httpCalls(sources));
        calls.addAll(declarativeCalls(properties, annotations, sources));
        calls.addAll(graphQlCalls(resources, sources));
        calls.addAll(manifest.calls());

        List<Subscription> consumes = new ArrayList<>();
        for (Subscription listener : annotations.kafkaListeners()) {
            consumes.add(new Subscription(listener.topic(), listener.via(), listener.group(), listener.payloadType(),
                    listener.source(), locate(sources, listener.location())));
        }
        List<Publication> publishes = new ArrayList<>();
        streamFunctions(properties, annotations, sources, consumes, publishes);
        for (SourceScanner.TemplateSend send : sources.kafkaSends()) {
            publishes.add(new Publication(send.topic(), "kafka-template", send.payloadType(), "static", send.location()));
        }
        for (SourceScanner.BridgeSend send : sources.bridgeSends()) {
            String destination = properties.get(BINDINGS + send.binding() + ".destination").orElse(send.binding());
            publishes.add(new Publication(destination, "stream-bridge", send.payloadType(), "static", send.location()));
        }
        publishes.addAll(manifest.publishes());
        consumes.addAll(manifest.consumes());

        return new ServiceGraph(
                service,
                repository(projectDir),
                commitSha(projectDir),
                Instant.now().toString(),
                exposes,
                distinct(calls),
                distinct(publishes),
                distinct(consumes),
                schemas(annotations.classIndex(), publishes, consumes),
                graphqlSchema.map(GraphQlScanner.Schema::sdl).orElse(null));
    }

    private List<HttpCall> httpCalls(SourceScanner.Result sources) {
        List<HttpCall> calls = new ArrayList<>();
        for (SourceScanner.ClientCall call : sources.httpCalls()) {
            var target = TargetServiceResolver.resolve(call.baseUrl());
            String baseUrl = call.baseUrl() == null ? null : call.baseUrl().raw();
            if (target.isEmpty()) {
                calls.add(new HttpCall("unknown", call.method(), call.path(), baseUrl, call.via(), "static", "low", call.location(), null));
                continue;
            }
            String path = call.path() == null ? null : joinPath(target.get().basePath(), call.path());
            String confidence = path == null ? "low" : target.get().confidence();
            calls.add(new HttpCall(target.get().service(), call.method(), path, baseUrl, call.via(), "static", confidence, call.location(), null));
        }
        return calls;
    }

    private List<HttpCall> declarativeCalls(SpringProperties properties, AnnotationScanner.Result annotations, SourceScanner.Result sources) {
        List<HttpCall> calls = new ArrayList<>();
        for (AnnotationScanner.DeclarativeClient client : annotations.declarativeClients()) {
            SourceScanner.BaseUrl baseUrl = client.url() != null
                    ? new SourceScanner.BaseUrl(client.url(), properties.resolve(client.url()), SpringProperties.placeholderKey(client.url()).orElse(null))
                    : sources.proxyBaseUrls().get(client.interfaceName().replace('$', '.'));
            Optional<TargetServiceResolver.Target> target = TargetServiceResolver.resolve(baseUrl);
            String service;
            String confidence;
            String basePath = target.map(TargetServiceResolver.Target::basePath).orElse("");
            if (target.isPresent()) {
                service = target.get().service();
                confidence = service.equals(client.serviceName()) ? "high" : target.get().confidence();
            } else if (client.serviceName() != null) {
                service = client.serviceName();
                confidence = baseUrl == null ? "high" : "medium";
            } else {
                service = "unknown";
                confidence = "low";
            }
            for (AnnotationScanner.ClientMethod method : client.methods()) {
                calls.add(new HttpCall(service, method.httpMethod(), joinPath(basePath, method.path()),
                        baseUrl == null ? null : baseUrl.raw(), client.kind(), "static", confidence,
                        sources.locate(client.interfaceName(), method.methodName()), null));
            }
        }
        return calls;
    }

    private List<HttpCall> graphQlCalls(Path resources, SourceScanner.Result sources) throws IOException {
        List<HttpCall> calls = new ArrayList<>();
        for (SourceScanner.GraphQlClientCall call : sources.graphQlCalls()) {
            String document = call.documentText();
            if (document == null && call.documentName() != null) {
                document = GraphQlScanner.loadDocument(resources, call.documentName()).orElse(null);
            }
            Optional<TargetServiceResolver.Target> target = TargetServiceResolver.resolve(call.baseUrl());
            String service = target.map(TargetServiceResolver.Target::service).orElse("unknown");
            String confidence = target.map(TargetServiceResolver.Target::confidence).orElse("low");
            String baseUrl = call.baseUrl() == null ? null : call.baseUrl().raw();
            List<GraphQlScanner.Operation> operations = document == null ? List.of() : GraphQlScanner.rootOperations(document);
            if (operations.isEmpty()) {
                calls.add(new HttpCall(service, "GRAPHQL", null, baseUrl, "graphql", "static", "low", call.location(), document));
            }
            for (GraphQlScanner.Operation operation : operations) {
                calls.add(new HttpCall(service, operation.type(), operation.field(), baseUrl, "graphql", "static", confidence, call.location(), document));
            }
        }
        return calls;
    }

    private static String joinPath(String base, String path) {
        String joined = ("/" + base + "/" + path).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private static String locate(SourceScanner.Result sources, String handler) {
        int hash = handler.indexOf('#');
        if (hash < 0) {
            return handler;
        }
        String method = handler.substring(hash + 1);
        int colon = method.indexOf(':');
        return sources.locate(handler.substring(0, hash), colon < 0 ? method : method.substring(0, colon));
    }

    private void streamFunctions(SpringProperties properties, AnnotationScanner.Result annotations, SourceScanner.Result sources,
                                 List<Subscription> consumes, List<Publication> publishes) {
        Map<String, AnnotationScanner.FunctionBean> beans = annotations.functionBeans();
        Set<String> active = new LinkedHashSet<>();
        properties.get("spring.cloud.function.definition")
                .ifPresentOrElse(
                        definition -> Arrays.stream(definition.split(";")).map(String::trim).filter(s -> !s.isEmpty()).forEach(active::add),
                        () -> {
                            if (beans.size() == 1) {
                                active.addAll(beans.keySet());
                            }
                        });
        for (String name : active) {
            AnnotationScanner.FunctionBean bean = beans.get(name);
            if (bean == null) {
                continue;
            }
            if (bean.inputType() != null) {
                String binding = name + "-in-0";
                String destination = properties.get(BINDINGS + binding + ".destination").orElse(binding);
                String group = properties.get(BINDINGS + binding + ".group").orElse(null);
                for (String topic : destination.split(",")) {
                    consumes.add(new Subscription(topic.trim(), "stream-function", group, bean.inputType(), "static", locate(sources, bean.location())));
                }
            }
            if (bean.outputType() != null) {
                String binding = name + "-out-0";
                String destination = properties.get(BINDINGS + binding + ".destination").orElse(binding);
                publishes.add(new Publication(destination, "stream-function", bean.outputType(), "static", locate(sources, bean.location())));
            }
        }
    }

    private List<PayloadSchema> schemas(ClassIndex index, List<Publication> publishes, List<Subscription> consumes) {
        Set<String> types = new LinkedHashSet<>();
        publishes.stream().map(Publication::payloadType).filter(Objects::nonNull).forEach(types::add);
        consumes.stream().map(Subscription::payloadType).filter(Objects::nonNull).forEach(types::add);
        List<PayloadSchema> schemas = new ArrayList<>();
        for (String type : types) {
            index.schemaOf(type).ifPresent(schemas::add);
        }
        return schemas;
    }

    private static <T> List<T> distinct(List<T> items) {
        return items.stream().distinct().toList();
    }

    private static String repository(Path projectDir) {
        String fromCi = System.getenv("CI_PROJECT_URL");
        if (fromCi != null && !fromCi.isBlank()) {
            return fromCi;
        }
        String remote = git(projectDir, "remote", "get-url", "origin");
        if (remote == null) {
            return null;
        }
        String https = remote.startsWith("git@") ? "https://" + remote.substring(4).replaceFirst(":", "/") : remote;
        return https.endsWith(".git") ? https.substring(0, https.length() - 4) : https;
    }

    private static String commitSha(Path projectDir) {
        String fromCi = System.getenv("CI_COMMIT_SHA");
        if (fromCi != null && !fromCi.isBlank()) {
            return fromCi;
        }
        String sha = git(projectDir, "rev-parse", "--short", "HEAD");
        return sha == null ? "unknown" : sha;
    }

    private static String git(Path projectDir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", projectDir.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 && !output.isEmpty() ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
