package com.example.graph.extractor.crawl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class CommandRunner {

    record Outcome(boolean success, String tail) {
    }

    private CommandRunner() {
    }

    static Outcome run(List<String> command, Path directory, Duration timeout, Path log) throws IOException {
        Files.createDirectories(log.getParent());
        Files.writeString(log, "$ " + String.join(" ", command) + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Outcome(false, "timed out after " + timeout.toMinutes() + " min");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Outcome(false, "interrupted");
        }
        return new Outcome(process.exitValue() == 0, tail(log));
    }

    private static String tail(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log).stream().filter(l -> !l.isBlank()).toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
    }
}
