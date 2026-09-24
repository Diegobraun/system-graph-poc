package com.example.graph.extractor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.support.FixtureProject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLayoutTest {

    @TempDir
    Path project;

    @Test
    void mergesMavenModulesIntoOneService() throws Exception {
        Path root = FixtureProject.prepare("fixtures/maven-multi-module", project);

        ProjectLayout layout = ProjectLayout.detect(root);
        ServiceGraph graph = new Extractor().extract(layout);

        assertEquals(List.of(root.resolve("client/target/classes"), root.resolve("app/target/classes")), layout.classpath());
        assertEquals("catalog-service", graph.service());
        assertEquals("GET /catalog/{sku}", graph.exposes().getFirst().method() + " " + graph.exposes().getFirst().path());
        assertEquals("pricing-service GET /prices/{sku}", graph.calls().getFirst().targetService() + " "
                + graph.calls().getFirst().method() + " " + graph.calls().getFirst().path());
        assertEquals("client/src/main/java/mm/client/PricingClient.java:10", graph.calls().getFirst().location());
    }

    @Test
    void findsGradleOutputDirectories() throws Exception {
        Path root = FixtureProject.prepare("fixtures/gradle", project);

        ProjectLayout layout = ProjectLayout.detect(root);
        ServiceGraph graph = new Extractor().extract(layout);

        assertEquals(List.of(root.resolve("service/build/classes/java/main")), layout.classpath());
        assertEquals("status-service", graph.service());
        assertEquals("GET /status", graph.exposes().getFirst().method() + " " + graph.exposes().getFirst().path());
    }
}
