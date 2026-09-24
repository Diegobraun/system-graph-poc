package com.example.graph.extractor.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.graph.extractor.scan.Extractor;
import com.example.graph.extractor.support.FixtureProject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlerTest {

    @TempDir
    Path workspace;

    @Test
    void extractsEveryProjectAndKeepsGoingAfterFailures() throws Exception {
        FixtureProject.prepare("fixtures/http-clients", workspace.resolve("clients"));
        FixtureProject.prepare("fixtures/messaging", workspace.resolve("messaging"));
        Files.createDirectories(workspace.resolve("broken"));
        Files.createDirectories(workspace.resolve("not-compiled/src/main/java"));
        Path config = workspace.resolve("crawl.yml");
        Files.writeString(config, """
                output: out
                ingest: false
                defaults:
                  build: skip
                projects:
                  - path: clients
                  - path: messaging
                  - path: broken
                    build: "echo compiling; exit 3"
                  - path: missing
                  - path: not-compiled
                """);

        ByteArrayOutputStream console = new ByteArrayOutputStream();
        List<Crawler.ProjectResult> results = new Crawler(new Extractor(), new PrintStream(console)).crawl(CrawlConfig.load(config));

        Map<String, String> statuses = results.stream().collect(Collectors.toMap(Crawler.ProjectResult::project, Crawler.ProjectResult::status));
        assertEquals(Map.of(
                "clients", "ok",
                "messaging", "ok",
                "broken", "build-failed",
                "missing", "git-failed",
                "not-compiled", "extract-failed"), statuses);
        assertTrue(Files.exists(workspace.resolve("out/fixture-service.json")));
        assertTrue(Files.exists(workspace.resolve("out/messaging-fixture.json")));
        assertTrue(Files.readString(workspace.resolve("out/logs/broken.log")).contains("compiling"));
        assertTrue(console.toString().contains("2 of 5 projects extracted"));
    }
}
