package com.example.graph.extractor.crawl;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public record CrawlConfig(Path outputDir, Defaults defaults, List<Project> projects, String kafkaBootstrap, boolean ingest,
                          String area, Map<String, String> neo4j, Map<String, String> hub) {

    public record Defaults(boolean pull, String build, boolean offline, Duration buildTimeout) {
    }

    public record Project(String name, Path path, String git, String branch, String build, boolean pull, String team) {
    }

    public static CrawlConfig load(Path file) throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file)) {
            root = new Yaml().load(reader);
        }
        if (root == null) {
            throw new IllegalArgumentException(file + " is empty");
        }
        Path base = file.toAbsolutePath().getParent();
        Map<String, Object> defaults = map(root.get("defaults"));
        Defaults resolvedDefaults = new Defaults(
                bool(defaults.get("pull"), true),
                text(defaults.get("build"), "auto"),
                bool(defaults.get("offline"), true),
                Duration.ofMinutes(number(defaults.get("buildTimeoutMinutes"), 10)));
        Path workspace = path(base, text(root.get("workspace"), "."));
        String team = text(root.get("team"), null);

        List<Project> projects = new ArrayList<>();
        Object items = root.get("projects");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(file + " has no projects");
        }
        for (Object item : list) {
            Map<String, Object> project = map(item);
            String git = text(project.get("git"), null);
            String pathText = text(project.get("path"), null);
            if (git == null && pathText == null) {
                throw new IllegalArgumentException("every project needs a path or a git url: " + project);
            }
            Path path = pathText != null ? path(base, pathText) : workspace.resolve(repositoryName(git));
            projects.add(new Project(
                    text(project.get("name"), path.getFileName().toString()),
                    path,
                    git,
                    text(project.get("branch"), null),
                    text(project.get("build"), resolvedDefaults.build()),
                    bool(project.get("pull"), resolvedDefaults.pull()),
                    text(project.get("team"), team)));
        }
        Map<String, Object> kafka = map(root.get("kafka"));
        return new CrawlConfig(
                path(base, text(root.get("output"), ".system-graph")),
                resolvedDefaults,
                projects,
                text(kafka.get("bootstrap"), null),
                bool(root.get("ingest"), true),
                text(root.get("area"), null),
                connection(root.get("neo4j")),
                connection(root.get("hub")));
    }

    public CrawlConfig withIngest(boolean value) {
        return new CrawlConfig(outputDir, defaults, projects, kafkaBootstrap, value, area, neo4j, hub);
    }

    public CrawlConfig withOverrides(Boolean pull, String build) {
        List<Project> changed = projects.stream()
                .map(p -> new Project(p.name(), p.path(), p.git(), p.branch(),
                        build == null ? p.build() : build, pull == null ? p.pull() : pull, p.team()))
                .toList();
        return new CrawlConfig(outputDir, defaults, changed, kafkaBootstrap, ingest, area, neo4j, hub);
    }

    public CrawlConfig only(List<String> names) {
        List<Project> selected = projects.stream().filter(p -> names.contains(p.name())).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("none of " + names + " is in the config");
        }
        return new CrawlConfig(outputDir, defaults, selected, kafkaBootstrap, ingest, area, neo4j, hub);
    }

    static String repositoryName(String gitUrl) {
        String name = gitUrl.replaceAll("/+$", "");
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf(':')) + 1);
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    private static Map<String, String> connection(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        map(value).forEach((key, v) -> {
            if (v != null) {
                result.put("neo4j-" + key, v.toString());
            }
        });
        return result;
    }

    private static Path path(Path base, String text) {
        String expanded = text.startsWith("~/") ? System.getProperty("user.home") + text.substring(1) : text;
        return base.resolve(expanded).normalize();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static long number(Object value, long fallback) {
        return value == null ? fallback : Long.parseLong(value.toString());
    }
}
