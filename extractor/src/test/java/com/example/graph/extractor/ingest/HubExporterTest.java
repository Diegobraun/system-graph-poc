package com.example.graph.extractor.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.graph.extractor.model.HttpCall;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class HubExporterTest {

    private final Predicate<HttpCall> leaves = HubExporter.leavesArea(Set.of("account-service", "customer-service"));

    @Test
    void keepsOnlyCallsThatLeaveTheArea() {
        assertTrue(leaves.test(call("loan-service")));
        assertTrue(leaves.test(call("bureau-service")));
        assertFalse(leaves.test(call("customer-service")));
    }

    @Test
    void dropsCallsWithoutTarget() {
        assertFalse(leaves.test(call(null)));
    }

    private static HttpCall call(String target) {
        return new HttpCall(target, "GET", "/x", null, "rest-client", "static", "high", "A.java:1", null);
    }
}
