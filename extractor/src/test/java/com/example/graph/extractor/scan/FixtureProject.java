package com.example.graph.extractor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

final class FixtureProject {

    private FixtureProject() {
    }

    static Path prepare(String resource, Path target) throws IOException, URISyntaxException {
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
        compile(target);
        return target;
    }

    private static void compile(Path project) throws IOException {
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        List<String> args = new ArrayList<>(List.of(
                "-d", classes.toString(),
                "-classpath", System.getProperty("java.class.path"),
                "-proc:none", "-parameters"));
        try (Stream<Path> sources = Files.walk(project.resolve("src/main/java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);
        }
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertEquals(0, status, "fixture compilation failed");
    }
}
