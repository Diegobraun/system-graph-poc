package com.example.graph.extractor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SpringPropertiesTest {

    private final SpringProperties properties = SpringProperties.of(Map.of(
            "services.account-service.url", "http://${account.host}:8081",
            "account.host", "account-service",
            "app.topics.loan-disbursed", "loan-disbursed"));

    @Test
    void resolvesNestedPlaceholders() {
        assertEquals("http://account-service:8081", properties.resolve("${services.account-service.url}"));
    }

    @Test
    void usesDefaultWhenKeyIsMissing() {
        assertEquals("fallback", properties.resolve("${missing.key:fallback}"));
    }

    @Test
    void keepsUnresolvablePlaceholder() {
        assertEquals("${missing.key}", properties.resolve("${missing.key}"));
    }

    @Test
    void extractsPlaceholderKey() {
        assertEquals("app.topics.loan-disbursed", SpringProperties.placeholderKey("${app.topics.loan-disbursed}").orElseThrow());
    }
}
