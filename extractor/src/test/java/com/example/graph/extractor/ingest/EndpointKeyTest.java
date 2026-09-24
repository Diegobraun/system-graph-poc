package com.example.graph.extractor.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EndpointKeyTest {

    @Test
    void ignoresPathVariableNames() {
        assertEquals(EndpointKey.of("account-service", "GET", "/accounts/{accountId}"),
                EndpointKey.of("account-service", "GET", "/accounts/{id}/"));
    }
}
