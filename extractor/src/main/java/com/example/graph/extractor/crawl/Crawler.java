package com.example.graph.extractor.crawl;

import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.scan.Extractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Crawler {

    public record ProjectResult(String project, String service, String status, String detail, Path graphFile,
                                ServiceGraph graph, Duration duration) {

        public boolean ok() {
            return "ok".equals(status);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Extractor extractor;
    private final PrintStream out;

    public Crawler(Extractor extractor, PrintStream out) {
        this.extractor = extractor;
        this.out = out;
    }

    public List<ProjectResult> crawl(CrawlConfig config) throws IOException {
        Path logs = Files.createDirectories(config.outputDir().resolve("logs"));
        List<ProjectResult> results = new ArrayList<>();
        Map<String, String> serviceOwners = new HashMap<>();
        int index = 0;
        for (CrawlConfig.Project project : config.projects()) {
            index++;
            out.printf("[%d/%d] %s%n", index, config.projects().size(), project.name());
            long start = System.nanoTime();
            Path log = logs.resolve(project.name() + ".log");
            Files.deleteIfExists(log);
            ProjectResult result = crawl(config, project, log, start);
            if (result.ok()) {
                String previous = serviceOwners.putIfAbsent(result.service(), project.name());
                if (previous != null) {
                    out.printf("      warning: %s also produced service %s, its graph overwrites the previous one%n", project.name(), result.service());
                }
            }
            out.printf("      %s%s (%.1fs)%n", result.status(), result.detail() == null ? "" : ": " + result.detail(),
                    result.duration().toMillis() / 1000.0);
            results.add(result);
        }
        printSummary(results);
        return results;
    }

    private ProjectResult crawl(CrawlConfig config, CrawlConfig.Project project, Path log, long start) {
        try {
            GitSync.Result git = GitSync.sync(project, log);
            if (!git.usable()) {
                return failure(project, "git-failed", git.detail(), start);
            }
            BuildRunner.Result build = BuildRunner.build(project.path(), project.build(), config.defaults().offline(),
                    config.defaults().buildTimeout(), log);
            if (!build.success()) {
                return failure(project, "build-failed", build.detail() + " (log: " + log + ")", start);
            }
            ServiceGraph graph = extractor.extract(project.path());
            Path file = config.outputDir().resolve(graph.service() + ".json");
            JSON.writeValue(file.toFile(), graph);
            return new ProjectResult(project.name(), graph.service(), "ok", git.detail(), file, graph, elapsed(start));
        } catch (Exception e) {
            return failure(project, "extract-failed", e.getMessage(), start);
        }
    }

    private void printSummary(List<ProjectResult> results) {
        out.println();
        out.printf("%-28s %-26s %-15s %9s %6s %5s %5s%n", "project", "service", "status", "endpoints", "calls", "pubs", "subs");
        for (ProjectResult result : results) {
            ServiceGraph graph = result.graph();
            out.printf("%-28s %-26s %-15s %9s %6s %5s %5s%n",
                    result.project(),
                    result.service() == null ? "-" : result.service(),
                    result.status(),
                    graph == null ? "-" : graph.exposes().size(),
                    graph == null ? "-" : graph.calls().size(),
                    graph == null ? "-" : graph.publishes().size(),
                    graph == null ? "-" : graph.consumes().size());
        }
        long failed = results.stream().filter(r -> !r.ok()).count();
        out.printf("%n%d of %d projects extracted%s%n", results.size() - failed, results.size(),
                failed == 0 ? "" : ", see the logs in the output directory for the failures");
    }

    private static ProjectResult failure(CrawlConfig.Project project, String status, String detail, long start) {
        return new ProjectResult(project.name(), null, status, detail, null, null, elapsed(start));
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
