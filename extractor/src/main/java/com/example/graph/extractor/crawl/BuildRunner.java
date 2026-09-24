package com.example.graph.extractor.crawl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class BuildRunner {

    record Result(boolean success, String detail) {
    }

    private BuildRunner() {
    }

    static Result build(Path project, String build, boolean offline, Duration timeout, Path log) throws IOException {
        if ("skip".equals(build)) {
            return new Result(true, "build skipped");
        }
        if (!"auto".equals(build)) {
            CommandRunner.Outcome outcome = CommandRunner.run(List.of("sh", "-c", build), project, timeout, log);
            return new Result(outcome.success(), outcome.success() ? "custom build" : outcome.tail());
        }
        List<List<String>> attempts = autoCommands(project, offline);
        if (attempts.isEmpty()) {
            return new Result(false, "no pom.xml or build.gradle found");
        }
        CommandRunner.Outcome outcome = null;
        for (List<String> command : attempts) {
            outcome = CommandRunner.run(command, project, timeout, log);
            if (outcome.success()) {
                return new Result(true, String.join(" ", command));
            }
        }
        return new Result(false, outcome.tail());
    }

    static List<List<String>> autoCommands(Path project, boolean offline) {
        List<String> base;
        String offlineFlag;
        if (Files.exists(project.resolve("pom.xml"))) {
            base = new ArrayList<>(List.of(Files.exists(project.resolve("mvnw")) ? "./mvnw" : "mvn", "-q", "-B", "compile", "-DskipTests"));
            offlineFlag = "-o";
        } else if (Files.exists(project.resolve("build.gradle")) || Files.exists(project.resolve("build.gradle.kts"))) {
            base = new ArrayList<>(List.of(Files.exists(project.resolve("gradlew")) ? "./gradlew" : "gradle", "-q", "classes"));
            offlineFlag = "--offline";
        } else {
            return List.of();
        }
        if (!offline) {
            return List.of(base);
        }
        List<String> withFlag = new ArrayList<>(base);
        withFlag.add(1, offlineFlag);
        return List.of(withFlag, base);
    }
}
