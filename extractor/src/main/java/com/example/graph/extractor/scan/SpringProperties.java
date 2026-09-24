package com.example.graph.extractor.scan;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public final class SpringProperties {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final int MAX_DEPTH = 10;

    private final Map<String, String> values;

    private SpringProperties(Map<String, String> values) {
        this.values = values;
    }

    public static SpringProperties load(Path resourcesDir) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Path properties = resourcesDir.resolve("application.properties");
        if (Files.exists(properties)) {
            try (InputStream in = Files.newInputStream(properties)) {
                Properties p = new Properties();
                p.load(in);
                p.forEach((k, v) -> values.put(k.toString(), v.toString()));
            }
        }
        for (String name : List.of("application.yml", "application.yaml")) {
            Path yaml = resourcesDir.resolve(name);
            if (Files.exists(yaml)) {
                try (Reader reader = Files.newBufferedReader(yaml)) {
                    for (Object document : new Yaml().loadAll(reader)) {
                        Map<String, String> flat = new LinkedHashMap<>();
                        flatten("", document, flat);
                        if (!flat.containsKey("spring.config.activate.on-profile")) {
                            values.putAll(flat);
                        }
                    }
                }
            }
        }
        return new SpringProperties(values);
    }

    public static SpringProperties of(Map<String, String> values) {
        return new SpringProperties(new LinkedHashMap<>(values));
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flatten(prefix.isEmpty() ? k.toString() : prefix + "." + k, v, out));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), out);
            }
        } else if (node != null) {
            out.put(prefix, node.toString());
        }
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key)).map(this::resolve);
    }

    public Map<String, String> withPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                result.put(k.substring(prefix.length()), resolve(v));
            }
        });
        return result;
    }

    public String resolve(String text) {
        String current = text;
        for (int depth = 0; depth < MAX_DEPTH && current != null && current.contains("${"); depth++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = values.get(matcher.group(1));
                if (replacement == null) {
                    replacement = matcher.group(2);
                }
                if (replacement == null) {
                    replacement = matcher.group(0);
                } else {
                    changed = true;
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            current = sb.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    public static Optional<String> placeholderKey(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
