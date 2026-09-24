package com.example.graph.extractor.scan;

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
        SpringProperties properties = SpringProperties.load(projectDir.resolve("src/main/resources"));
        Manifest manifest = Manifest.load(projectDir);
        String service = properties.get("spring.application.name")
                .or(() -> Optional.ofNullable(manifest.service()))
                .orElse(projectDir.getFileName().toString());

        AnnotationScanner.Result annotations = new AnnotationScanner(properties).scan(classes);
        SourceScanner.Result sources = new SourceScanner(projectDir, properties, annotations.classIndex()).scan();

        List<HttpCall> calls = new ArrayList<>(httpCalls(sources));
        calls.addAll(manifest.calls());

        List<Subscription> consumes = new ArrayList<>(annotations.kafkaListeners());
        List<Publication> publishes = new ArrayList<>();
        streamFunctions(properties, annotations, consumes, publishes);
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
                commitSha(projectDir),
                Instant.now().toString(),
                annotations.exposes(),
                distinct(calls),
                distinct(publishes),
                distinct(consumes),
                schemas(annotations.classIndex(), publishes, consumes));
    }

    private List<HttpCall> httpCalls(SourceScanner.Result sources) {
        List<HttpCall> calls = new ArrayList<>();
        for (SourceScanner.ClientCall call : sources.httpCalls()) {
            var target = TargetServiceResolver.resolve(call.baseUrl());
            String baseUrl = call.baseUrl() == null ? null : call.baseUrl().raw();
            if (target.isEmpty()) {
                calls.add(new HttpCall("unknown", call.method(), call.path(), baseUrl, "static", "low", call.location()));
                continue;
            }
            String path = call.path() == null ? null : (target.get().basePath() + "/" + call.path()).replaceAll("/+", "/");
            String confidence = path == null ? "low" : target.get().confidence();
            calls.add(new HttpCall(target.get().service(), call.method(), path, baseUrl, "static", confidence, call.location()));
        }
        return calls;
    }

    private void streamFunctions(SpringProperties properties, AnnotationScanner.Result annotations,
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
                    consumes.add(new Subscription(topic.trim(), "stream-function", group, bean.inputType(), "static", bean.location()));
                }
            }
            if (bean.outputType() != null) {
                String binding = name + "-out-0";
                String destination = properties.get(BINDINGS + binding + ".destination").orElse(binding);
                publishes.add(new Publication(destination, "stream-function", bean.outputType(), "static", bean.location()));
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

    private static String commitSha(Path projectDir) {
        String fromCi = System.getenv("CI_COMMIT_SHA");
        if (fromCi != null && !fromCi.isBlank()) {
            return fromCi;
        }
        try {
            Process process = new ProcessBuilder("git", "-C", projectDir.toString(), "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 && !output.isEmpty() ? output : "unknown";
        } catch (IOException | InterruptedException e) {
            return "unknown";
        }
    }
}
