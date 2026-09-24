package com.example.graph.extractor.scan;

import com.example.graph.extractor.model.HttpCall;
import com.example.graph.extractor.model.Publication;
import com.example.graph.extractor.model.Subscription;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public record Manifest(String service, List<HttpCall> calls, List<Publication> publishes, List<Subscription> consumes) {

    public static final String FILE_NAME = "system-graph.yml";

    public static Manifest empty() {
        return new Manifest(null, List.of(), List.of(), List.of());
    }

    public static Manifest load(Path projectDir) throws IOException {
        Path file = projectDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return empty();
        }
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file)) {
            root = new Yaml().load(reader);
        }
        if (root == null) {
            return empty();
        }
        String location = FILE_NAME;
        List<HttpCall> calls = new ArrayList<>();
        for (Map<String, Object> item : items(root, "calls")) {
            calls.add(new HttpCall(text(item, "service"), upper(text(item, "method")), text(item, "path"), null, "manifest", "manifest", "declared", location, null));
        }
        List<Publication> publishes = new ArrayList<>();
        for (Map<String, Object> item : items(root, "publishes")) {
            publishes.add(new Publication(text(item, "topic"), "manifest", text(item, "payloadType"), "manifest", location));
        }
        List<Subscription> consumes = new ArrayList<>();
        for (Map<String, Object> item : items(root, "consumes")) {
            consumes.add(new Subscription(text(item, "topic"), "manifest", text(item, "group"), text(item, "payloadType"), "manifest", location));
        }
        return new Manifest(text(root, "service"), calls, publishes, consumes);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> root, String key) {
        Object value = root.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
