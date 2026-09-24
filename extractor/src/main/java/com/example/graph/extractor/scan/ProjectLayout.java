package com.example.graph.extractor.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ProjectLayout(Path root, List<Path> classpath, List<Path> sourceDirs, List<Path> resourceDirs) {

    private static final Pattern MAVEN_MODULES = Pattern.compile("<modules>(.*?)</modules>", Pattern.DOTALL);
    private static final Pattern MAVEN_MODULE = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
    private static final Pattern GRADLE_INCLUDE = Pattern.compile("^\\s*include\\s*\\(?(.+?)\\)?\\s*$", Pattern.MULTILINE);
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final List<String> CLASS_DIRS = List.of("target/classes", "build/classes/java/main", "build/classes/kotlin/main");

    public static ProjectLayout detect(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Set<Path> modules = new LinkedHashSet<>();
        collectModules(normalized, modules);
        List<Path> classpath = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> resources = new ArrayList<>();
        for (Path module : modules) {
            CLASS_DIRS.stream().map(module::resolve).filter(Files::isDirectory).forEach(classpath::add);
            existing(module.resolve("src/main/java")).ifPresent(sources::add);
            existing(module.resolve("src/main/resources")).ifPresent(resources::add);
        }
        return new ProjectLayout(normalized, classpath, sources, resources);
    }

    private static void collectModules(Path dir, Set<Path> modules) throws IOException {
        if (!modules.add(dir)) {
            return;
        }
        Path pom = dir.resolve("pom.xml");
        if (Files.exists(pom)) {
            Matcher block = MAVEN_MODULES.matcher(Files.readString(pom));
            while (block.find()) {
                Matcher module = MAVEN_MODULE.matcher(block.group(1));
                while (module.find()) {
                    Path child = dir.resolve(module.group(1)).normalize();
                    if (Files.isDirectory(child)) {
                        collectModules(child, modules);
                    }
                }
            }
        }
        for (String settings : List.of("settings.gradle", "settings.gradle.kts")) {
            Path file = dir.resolve(settings);
            if (Files.exists(file)) {
                Matcher include = GRADLE_INCLUDE.matcher(Files.readString(file));
                while (include.find()) {
                    Matcher name = QUOTED.matcher(include.group(1));
                    while (name.find()) {
                        Path child = dir.resolve(name.group(1).replaceFirst("^:", "").replace(':', '/')).normalize();
                        if (Files.isDirectory(child)) {
                            collectModules(child, modules);
                        }
                    }
                }
            }
        }
    }

    private static Optional<Path> existing(Path path) {
        return Files.isDirectory(path) ? Optional.of(path) : Optional.empty();
    }
}
