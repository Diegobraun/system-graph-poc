package com.example.graph.extractor.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlConfigTest {

    @TempDir
    Path dir;

    @Test
    void resolvesPathsAndDefaults() throws Exception {
        Path file = dir.resolve("crawl.yml");
        Files.writeString(file, """
                workspace: repos
                defaults:
                  pull: false
                  buildTimeoutMinutes: 3
                projects:
                  - path: ~/work/conta-api
                  - git: git@gitlab.empresa.com.br:credito/limite-service.git
                    branch: develop
                    build: skip
                """);

        CrawlConfig config = CrawlConfig.load(file);

        assertEquals(dir.resolve(".system-graph"), config.outputDir());
        assertEquals(Duration.ofMinutes(3), config.defaults().buildTimeout());
        CrawlConfig.Project conta = config.projects().get(0);
        assertEquals(Path.of(System.getProperty("user.home"), "work/conta-api"), conta.path());
        assertEquals("conta-api", conta.name());
        assertEquals("auto", conta.build());
        assertFalse(conta.pull());
        CrawlConfig.Project limite = config.projects().get(1);
        assertEquals(dir.resolve("repos/limite-service"), limite.path());
        assertEquals("develop", limite.branch());
        assertEquals("skip", limite.build());
    }

    @Test
    void choosesOfflineBuildFirstWithOnlineFallback() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("mvnw"), "");

        assertEquals(List.of(
                List.of("./mvnw", "-o", "-q", "-B", "compile", "-DskipTests"),
                List.of("./mvnw", "-q", "-B", "compile", "-DskipTests")), BuildRunner.autoCommands(dir, true));
    }

    @Test
    void usesGradleWhenThereIsNoPom() throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), "");

        assertEquals(List.of(List.of("gradle", "-q", "classes")), BuildRunner.autoCommands(dir, false));
    }
}
