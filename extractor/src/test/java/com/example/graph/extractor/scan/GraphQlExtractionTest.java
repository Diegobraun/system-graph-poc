package com.example.graph.extractor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.graph.extractor.model.ServiceGraph;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphQlExtractionTest {

    @TempDir
    static Path project;

    static ServiceGraph graph;

    @BeforeAll
    static void extractFixture() throws Exception {
        graph = new Extractor().extract(FixtureProject.prepare("fixtures/graphql", project));
    }

    @Test
    void exposesRootFieldsFromSchemaFilesIncludingExtensions() {
        Set<String> exposed = graph.exposes().stream()
                .map(e -> e.method() + " " + e.path() + " " + e.handler().replaceAll(":\\d+$", ""))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "QUERY rates demo.RatesController#rates",
                "QUERY health demo.RatesController#status",
                "MUTATION transfer demo.RatesController#doTransfer"), exposed);
    }

    @Test
    void readsInlineAndNamedDocuments() {
        Set<String> calls = graph.calls().stream()
                .map(c -> String.join(" ", c.via(), c.targetService(), c.method(), c.path(), c.confidence()))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "graphql report-service QUERY reports high",
                "graphql report-service QUERY kpis high",
                "graphql report-service MUTATION closeDay high",
                "graphql ledger-service QUERY balance high"), calls);
    }

    @Test
    void keepsSchemaAndDocumentsForCrossServiceValidation() {
        assertTrue(graph.graphqlSchema().contains("extend type Query"));
        assertTrue(graph.calls().stream().allMatch(c -> c.document() != null && !c.document().isBlank()));
    }
}
