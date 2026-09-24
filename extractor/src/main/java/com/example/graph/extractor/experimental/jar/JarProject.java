package com.example.graph.extractor.experimental.jar;

import com.example.graph.extractor.scan.ProjectLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarProject {

    private static final String BOOT_CLASSES = "BOOT-INF/classes/";
    private static final String BOOT_LIB = "BOOT-INF/lib/";
    private static final Set<String> RESOURCE_PREFIXES = Set.of("application", "bootstrap", "graphql/", "graphql-documents/");

    public record Unpacked(ProjectLayout layout, String commit, String repository, List<String> includedLibraries) {
    }

    private JarProject() {
    }

    public static Unpacked unpack(Path jar, Path sources, List<String> libraryPatterns, Path workDir) throws IOException {
        Path root = workDir.toAbsolutePath().normalize();
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        Path resources = Files.createDirectories(root.resolve("src/main/resources"));
        List<PathMatcher> matchers = libraryPatterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        List<Path> classpath = new ArrayList<>(List.of(classes));
        List<String> included = new ArrayList<>();
        Properties git = new Properties();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            boolean boot = zip.stream().anyMatch(e -> e.getName().startsWith(BOOT_CLASSES));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (boot && name.startsWith(BOOT_LIB)) {
                    String library = name.substring(BOOT_LIB.length());
                    if (matchers.stream().anyMatch(m -> m.matches(Path.of(library)))) {
                        classpath.add(copy(zip, entry, root, "lib/" + library));
                        included.add(library);
                    }
                    continue;
                }
                String relative = boot ? (name.startsWith(BOOT_CLASSES) ? name.substring(BOOT_CLASSES.length()) : null) : name;
                if (relative == null || relative.startsWith("META-INF/") || relative.startsWith("org/springframework/boot/loader/")) {
                    continue;
                }
                copy(zip, entry, root, "target/classes/" + relative);
                if (RESOURCE_PREFIXES.stream().anyMatch(relative::startsWith)) {
                    copy(zip, entry, root, "src/main/resources/" + relative);
                }
                if (relative.equals("git.properties")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        git.load(in);
                    }
                }
            }
        }

        List<Path> sourceDirs = new ArrayList<>();
        if (sources != null && Files.isDirectory(sources)) {
            sourceDirs.add(sources.toAbsolutePath().normalize());
        } else if (sources != null) {
            try (ZipFile zip = new ZipFile(sources.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".java")) {
                        copy(zip, entry, root, "src/main/java/" + entry.getName());
                    }
                }
            }
            sourceDirs.add(root.resolve("src/main/java"));
        }
        ProjectLayout layout = new ProjectLayout(root, classpath, sourceDirs, List.of(resources));
        return new Unpacked(layout, git.getProperty("git.commit.id.abbrev", git.getProperty("git.commit.id")),
                git.getProperty("git.remote.origin.url"), included);
    }

    private static Path copy(ZipFile zip, ZipEntry entry, Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("jar entry escapes the work directory: " + entry.getName());
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
