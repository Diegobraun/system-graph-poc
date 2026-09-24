package com.example.graph.extractor.crawl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class GitSync {

    record Result(boolean usable, String detail) {
    }

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private GitSync() {
    }

    static Result sync(CrawlConfig.Project project, Path log) throws IOException {
        Path dir = project.path();
        if (!Files.isDirectory(dir)) {
            if (project.git() == null) {
                return new Result(false, "directory not found and no git url to clone");
            }
            Files.createDirectories(dir.getParent());
            List<String> clone = new ArrayList<>(List.of("git", "clone", "-q"));
            if (project.branch() != null) {
                clone.addAll(List.of("-b", project.branch()));
            }
            clone.addAll(List.of(project.git(), dir.toString()));
            CommandRunner.Outcome outcome = CommandRunner.run(clone, dir.getParent(), TIMEOUT, log);
            return new Result(outcome.success(), outcome.success() ? "cloned" : "clone failed: " + outcome.tail());
        }
        if (!project.pull() || !Files.isDirectory(dir.resolve(".git"))) {
            return new Result(true, "local");
        }
        CommandRunner.Outcome outcome = CommandRunner.run(List.of("git", "pull", "-q", "--ff-only"), dir, TIMEOUT, log);
        return new Result(true, outcome.success() ? "pulled" : "pull failed, using local copy");
    }
}
