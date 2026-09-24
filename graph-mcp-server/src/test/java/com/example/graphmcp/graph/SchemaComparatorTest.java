package com.example.graphmcp.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.graphmcp.graph.SchemaComparator.SchemaIssue;
import com.example.graphmcp.graph.SchemaComparator.SchemaView;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaComparatorTest {

    private static final SchemaView PRODUCER = new SchemaView("account-service", "producer", "AccountOpenedEvent",
            List.of("accountId", "customerId", "openedAt"), List.of("Long", "Long", "Instant"));

    @Test
    void flagsFieldExpectedByConsumerButNeverSent() {
        SchemaView consumer = new SchemaView("loan-service", "consumer", "AccountOpenedEvent",
                List.of("accountId", "customerId", "monthlyIncome"), List.of("Long", "Long", "BigDecimal"));

        List<SchemaIssue> issues = SchemaComparator.compare("account-opened", List.of(PRODUCER, consumer));

        assertThat(issues).extracting(SchemaIssue::severity, SchemaIssue::field)
                .containsExactlyInAnyOrder(
                        tuple("warning", "monthlyIncome"),
                        tuple("info", "openedAt"));
    }

    @Test
    void flagsTypeMismatch() {
        SchemaView consumer = new SchemaView("loan-service", "consumer", "AccountOpenedEvent",
                List.of("accountId", "customerId"), List.of("String", "Long"));

        List<SchemaIssue> issues = SchemaComparator.compare("account-opened", List.of(PRODUCER, consumer));

        assertThat(issues).filteredOn(i -> i.severity().equals("error"))
                .singleElement()
                .extracting(SchemaIssue::field)
                .isEqualTo("accountId");
    }

    @Test
    void compatibleSchemasHaveNoProblems() {
        SchemaView consumer = new SchemaView("loan-service", "consumer", "AccountOpenedEvent",
                List.of("accountId", "customerId", "openedAt"), List.of("Long", "Long", "Instant"));

        assertThat(SchemaComparator.compare("account-opened", List.of(PRODUCER, consumer))).isEmpty();
    }
}
