package com.example.graphmcp.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphQlAnalyzerTest {

    private static final String SDL = """
            type Query {
                customer(id: ID!): Customer
            }
            type Customer {
                id: ID!
                name: String!
                monthlyIncome: Float!
                accounts: [Account!]!
            }
            type Account {
                id: ID!
                balance: Float!
            }
            """;

    @Test
    void listsEveryFieldTheClientSelects() {
        var analysis = GraphQlAnalyzer.analyze(SDL, """
                query profile($id: ID!) {
                    customer(id: $id) { name monthlyIncome accounts { balance } }
                }
                """);

        assertThat(analysis.valid()).isTrue();
        assertThat(analysis.fieldsUsed()).containsExactly(
                "Account.balance", "Customer.accounts", "Customer.monthlyIncome", "Customer.name", "Query.customer");
    }

    @Test
    void reportsFieldsThatTheServerDoesNotHave() {
        var analysis = GraphQlAnalyzer.analyze(SDL, "query { customer(id: 1) { name riskScore } }");

        assertThat(analysis.valid()).isFalse();
        assertThat(analysis.errors()).singleElement().asString().contains("riskScore");
    }

    @Test
    void checksTypeFieldExistence() {
        assertThat(GraphQlAnalyzer.hasField(SDL, "Customer.monthlyIncome")).isTrue();
        assertThat(GraphQlAnalyzer.hasField(SDL, "Customer.email")).isFalse();
    }
}
