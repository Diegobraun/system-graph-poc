package com.example.graph.extractor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.graph.extractor.model.PayloadSchema;
import com.example.graph.extractor.model.SchemaField;
import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.support.FixtureProject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagingExtractionTest {

    @TempDir
    static Path project;

    static ServiceGraph graph;

    @BeforeAll
    static void extractFixture() throws Exception {
        graph = new Extractor().extract(FixtureProject.prepare("fixtures/messaging", project));
    }

    @Test
    void readsListenersAndStreamFunctions() {
        Set<String> consumes = graph.consumes().stream()
                .map(c -> String.join(" ", c.via(), c.topic(), String.valueOf(c.group()), c.payloadType()))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "kafka-listener payments-settled messaging-fixture demo.messaging.PaymentSettled",
                "kafka-listener refunds refunds-team demo.messaging.RefundIssued",
                "stream-function invoice-requested messaging-fixture demo.messaging.InvoiceRequested",
                "stream-function risk-requests null demo.messaging.RiskRequest"), consumes);
    }

    @Test
    void readsTemplateBridgeAndFunctionOutputs() {
        Set<String> publishes = graph.publishes().stream()
                .map(p -> String.join(" ", p.via(), p.topic(), p.payloadType()))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "kafka-template orders demo.messaging.OrderPlaced",
                "stream-bridge audit-log demo.messaging.AuditEntry",
                "stream-function risk-scored demo.messaging.RiskScore"), publishes);
    }

    @Test
    void capturesPayloadFields() {
        PayloadSchema score = graph.schemas().stream()
                .filter(s -> s.className().equals("demo.messaging.RiskScore"))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of(new SchemaField("customerId", "String"), new SchemaField("score", "int")), score.fields());
    }

    @Test
    void pointsListenersToSourceLines() {
        assertEquals(Set.of("src/main/java/demo/messaging/PaymentListener.java:7", "src/main/java/demo/messaging/RefundListener.java:9"),
                graph.consumes().stream().filter(c -> c.via().equals("kafka-listener")).map(c -> c.location()).collect(Collectors.toSet()));
    }
}
