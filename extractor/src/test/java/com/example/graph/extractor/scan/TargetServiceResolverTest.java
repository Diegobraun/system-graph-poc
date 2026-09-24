package com.example.graph.extractor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TargetServiceResolverTest {

    @Test
    void usesHostWhenItIsAServiceName() {
        var target = TargetServiceResolver.resolve(new SourceScanner.BaseUrl("${x}", "http://account-service.core.svc:8080/api", "x")).orElseThrow();

        assertEquals("account-service", target.service());
        assertEquals("/api", target.basePath());
        assertEquals("high", target.confidence());
    }

    @Test
    void fallsBackToPropertyKeyConventionForLocalhost() {
        var target = TargetServiceResolver.resolve(new SourceScanner.BaseUrl("${services.account-service.url}", "http://localhost:8081", "services.account-service.url")).orElseThrow();

        assertEquals("account-service", target.service());
        assertEquals("medium", target.confidence());
    }

    @Test
    void givesUpWhenNothingIdentifiesTheService() {
        assertTrue(TargetServiceResolver.resolve(new SourceScanner.BaseUrl("http://localhost:9000", "http://localhost:9000", null)).isEmpty());
    }
}
