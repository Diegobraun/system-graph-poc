package com.example.graph.extractor.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.graph.extractor.experimental.observed.DynatraceClient;
import com.example.graph.extractor.experimental.observed.ObservedCall;
import com.example.graph.extractor.experimental.observed.ObservedCallsImporter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ObservedCallsTest {

    @Test
    void normalizesNamesAndMergesDuplicates() {
        List<ObservedCall> calls = ObservedCallsImporter.normalize(List.of(
                new ObservedCall("Loan Service", "account-service", 10L),
                new ObservedCall("loan-service", "ACCOUNT-SERVICE", 5L),
                new ObservedCall("SpringBoot loan-app", "customer-db", 1L),
                new ObservedCall("account-service", "Account Service", 3L)),
                Map.of("SpringBoot loan-app", "loan-service", "customer-db", ""));

        assertEquals(List.of(new ObservedCall("loan-service", "account-service", 15L)), calls);
    }

    @Test
    void readsServiceCallsFromDynatraceAcrossPages() throws Exception {
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/entities", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String query = exchange.getRequestURI().getRawQuery();
            String body = query.contains("nextPageKey")
                    ? """
                      {"entities": [{"entityId": "SERVICE-2", "displayName": "account-service"},
                                    {"entityId": "SERVICE-3", "displayName": "customer-service",
                                     "fromRelationships": {"calls": [{"id": "SERVICE-2"}]}}]}
                      """
                    : """
                      {"nextPageKey": "p2",
                       "entities": [{"entityId": "SERVICE-1", "displayName": "loan-service",
                                     "fromRelationships": {"calls": [{"id": "SERVICE-2"}, {"id": "SERVICE-9"}]}}]}
                      """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            List<ObservedCall> calls = new DynatraceClient(base, "secret", HttpClient.newHttpClient()).serviceCalls();

            assertEquals(List.of(
                    new ObservedCall("loan-service", "account-service", null),
                    new ObservedCall("customer-service", "account-service", null)), calls);
            assertEquals(List.of("Api-Token secret", "Api-Token secret"), authorizations);
        } finally {
            server.stop(0);
        }
    }
}
