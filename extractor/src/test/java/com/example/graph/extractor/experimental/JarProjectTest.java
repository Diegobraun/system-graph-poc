package com.example.graph.extractor.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.graph.extractor.experimental.jar.JarProject;
import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.scan.Extractor;
import com.example.graph.extractor.support.FixtureProject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarProjectTest {

    @TempDir
    static Path temp;

    static Path project;
    static Path bootJar;

    @BeforeAll
    static void buildBootJar() throws Exception {
        project = FixtureProject.prepare("fixtures/http-clients", temp.resolve("project"));
        Path classes = project.resolve("target/classes");
        Path resources = project.resolve("src/main/resources");
        byte[] ratesLibrary = zip(out -> add(out, "demo/RatesClient.class", Files.readAllBytes(classes.resolve("demo/RatesClient.class"))));
        bootJar = temp.resolve("fixture-service.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bootJar))) {
            add(out, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = classes.relativize(file).toString();
                    if (!name.equals("demo/RatesClient.class")) {
                        add(out, "BOOT-INF/classes/" + name, Files.readAllBytes(file));
                    }
                }
            }
            add(out, "BOOT-INF/classes/application.yml", Files.readAllBytes(resources.resolve("application.yml")));
            add(out, "BOOT-INF/classes/git.properties",
                    "git.commit.id.abbrev=abc1234\ngit.remote.origin.url=https://gitlab.example/team/fixture-service\n"
                            .getBytes(StandardCharsets.UTF_8));
            add(out, "BOOT-INF/lib/rates-client-1.0.jar", ratesLibrary);
            add(out, "BOOT-INF/lib/jackson-core-2.0.jar", zip(o -> add(o, "x/Y.class", new byte[0])));
        }
    }

    @Test
    void readsBootJarWithSourcesAndSelectedLibraries() throws Exception {
        JarProject.Unpacked unpacked = JarProject.unpack(bootJar, project.resolve("src/main/java"),
                List.of("rates-*.jar"), Files.createDirectory(temp.resolve("full")));
        ServiceGraph graph = new Extractor().extract(unpacked.layout());

        assertEquals("fixture-service", graph.service());
        assertEquals("abc1234", unpacked.commit());
        assertEquals("https://gitlab.example/team/fixture-service", unpacked.repository());
        assertEquals(List.of("rates-client-1.0.jar"), unpacked.includedLibraries());
        Set<String> calls = calls(graph);
        assertTrue(calls.contains("feign rates-service GET /rates/{currency}"));
        assertTrue(calls.contains("rest-client profile-service GET /profiles/{id}"));
        assertTrue(calls.contains("feign loan-service POST /loans"));
    }

    @Test
    void withoutSourcesKeepsOnlyWhatBytecodeShows() throws Exception {
        JarProject.Unpacked unpacked = JarProject.unpack(bootJar, null, List.of(), Files.createDirectory(temp.resolve("bare")));
        Set<String> calls = calls(new Extractor().extract(unpacked.layout()));

        assertTrue(calls.contains("feign loan-service POST /loans"));
        assertFalse(calls.contains("feign rates-service GET /rates/{currency}"));
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("rest-client")));
    }

    @Test
    void rejectsEntriesThatEscapeTheWorkDirectory() throws Exception {
        Path evil = temp.resolve("evil.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(evil))) {
            add(out, "../../../outside.txt", "x".getBytes(StandardCharsets.UTF_8));
        }
        Path work = Files.createDirectory(temp.resolve("evil"));

        assertThrows(IOException.class, () -> JarProject.unpack(evil, null, List.of(), work));
        assertFalse(Files.exists(temp.resolve("outside.txt")));
    }

    private static Set<String> calls(ServiceGraph graph) {
        return graph.calls().stream()
                .map(c -> String.join(" ", c.via(), c.targetService(), c.method(), c.path()))
                .collect(Collectors.toSet());
    }

    private interface Entries {
        void write(ZipOutputStream out) throws IOException;
    }

    private static byte[] zip(Entries entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            entries.write(out);
        }
        return bytes.toByteArray();
    }

    private static void add(ZipOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content);
        out.closeEntry();
    }
}
