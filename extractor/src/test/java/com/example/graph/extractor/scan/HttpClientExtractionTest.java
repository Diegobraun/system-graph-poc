package com.example.graph.extractor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.graph.extractor.model.HttpCall;
import com.example.graph.extractor.model.ServiceGraph;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpClientExtractionTest {

    @TempDir
    static Path project;

    static ServiceGraph graph;

    @BeforeAll
    static void extractFixture() throws Exception {
        graph = new Extractor().extract(FixtureProject.prepare("fixtures/http-clients", project));
    }

    @Test
    void readsEveryClientStyle() {
        Set<String> calls = graph.calls().stream()
                .map(c -> String.join(" ", c.via(), c.targetService(), c.method(), c.path(), c.confidence()))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "http-exchange account-service GET /accounts/{id} medium",
                "http-exchange account-service POST /accounts medium",
                "http-exchange payments-service POST /v1/payments high",
                "http-exchange wallet-service GET /wallets/{id} high",
                "feign loan-service GET /loans high",
                "feign loan-service POST /loans high",
                "feign rates-service GET /rates/{currency} high",
                "rest-client profile-service GET /profiles/{id} medium"), calls);
    }

    @Test
    void pointsDeclarativeCallsToTheInterfaceMethodInSource() {
        HttpCall call = graph.calls().stream()
                .filter(c -> c.targetService().equals("loan-service") && c.method().equals("POST"))
                .findFirst()
                .orElseThrow();

        assertEquals("src/main/java/demo/LoanClient.java:16", call.location());
    }

    @Test
    void keepsTheRawBaseUrlExpression() {
        assertTrue(graph.calls().stream()
                .anyMatch(c -> c.targetService().equals("account-service") && "${services.account-service.url}".equals(c.baseUrl())));
    }
}
