package com.example.graph.extractor.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

public final class FixtureProject {

    private FixtureProject() {
    }

    public static Path prepare(String resource, Path target) throws IOException, URISyntaxException {
        copy(resource, target);
        compile(target);
        return target;
    }

    public static Path copy(String resource, Path target) throws IOException, URISyntaxException {
        Path source = Path.of(FixtureProject.class.getClassLoader().getResource(resource).toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(file, destination);
                }
            }
        }
        return target;
    }

    private static void compile(Path project) throws IOException {
        List<Path> modules;
        try (Stream<Path> dirs = Files.walk(project)) {
            modules = dirs.filter(dir -> dir.endsWith(Path.of("src/main/java")))
                    .map(dir -> dir.getParent().getParent().getParent())
                    .sorted()
                    .toList();
        }
        StringBuilder classpath = new StringBuilder(System.getProperty("java.class.path"));
        for (Path module : modules) {
            boolean gradle = Files.exists(module.resolve("build.gradle")) || Files.exists(module.resolve("build.gradle.kts"));
            Path classes = Files.createDirectories(module.resolve(gradle ? "build/classes/java/main" : "target/classes"));
            List<String> args = new ArrayList<>(List.of(
                    "-d", classes.toString(),
                    "-classpath", classpath.toString(),
                    "-proc:none", "-parameters"));
            try (Stream<Path> sources = Files.walk(module.resolve("src/main/java"))) {
                sources.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);
            }
            int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
            assertEquals(0, status, "fixture compilation failed for " + module);
            classpath.append(File.pathSeparator).append(classes);
        }
    }
}
