package com.example.graphmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FederationTest {

    private final Map<String, Map<String, Object>> catalog = Federation.byName(List.of(
            service("customer-service", "contas", "Cadastro"),
            service("account-service", "contas", "Contas"),
            service("loan-service", "credito", "Risco"),
            service("investment-service", "credito", "Investimentos"),
            service("payment-service", "pagamentos", "PIX")));

    @Test
    void unionKeepsLocalAndAddsWhatOnlyTheHubKnows() {
        List<Map<String, Object>> merged = Federation.union(
                List.of(Map.of("service", "account-service", "location", "A.java:1")),
                List.of(Map.of("service", "account-service", "location", "hub"), Map.of("service", "loan-service", "location", "L.java:9")),
                m -> m.get("service"));

        assertEquals(2, merged.size());
        assertEquals("A.java:1", merged.get(0).get("location"));
        assertEquals("loan-service", merged.get(1).get("service"));
        assertEquals("hub", merged.get(1).get("origin"));
    }

    @Test
    void mergesCallersOfTheSameEndpoint() {
        List<Map<String, Object>> merged = Federation.mergeMatching(
                List.of(Map.of("key", "customer-service GET /x", "callers", List.of(Map.of("service", "account-service", "location", "A:1")))),
                List.of(Map.of("key", "customer-service GET /x", "callers", List.of(Map.of("service", "loan-service", "location", "L:2")))),
                e -> e.get("key"), "callers", c -> c.get("service") + "|" + c.get("location"));

        List<Map<String, Object>> callers = Federation.maps(merged.getFirst().get("callers"));
        assertEquals(List.of("account-service", "loan-service"), callers.stream().map(c -> c.get("service")).toList());
    }

    @Test
    void groupsAffectedServicesByAreaWithoutTheOwnerArea() {
        List<Map<String, Object>> areas = Federation.affectedAreas(
                List.of("account-service", "loan-service", "investment-service", "payment-service", "bureau-service"), catalog, "contas");

        assertEquals(2, areas.size());
        assertEquals("credito", areas.get(0).get("area"));
        assertEquals(List.of("Investimentos", "Risco"), areas.get(0).get("teams"));
        assertEquals(List.of("investment-service", "loan-service"), areas.get(0).get("services"));
        assertEquals("pagamentos", areas.get(1).get("area"));
    }

    @Test
    void annotatesAreaAndTeamWhenMissing() {
        List<Map<String, Object>> entries = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("service", "loan-service"))));

        Federation.annotate(entries, catalog);

        assertEquals("credito", entries.getFirst().get("area"));
        assertEquals("Risco", entries.getFirst().get("team"));
    }

    @Test
    void crossAreaIssuesComeFromTheHub() {
        Set<String> mine = Set.of("loan-service", "investment-service");
        Map<String, Object> toOtherArea = issue("http", Map.of("caller", "loan-service", "target", "customer-service"));
        Map<String, Object> inside = issue("http", Map.of("caller", "loan-service", "target", "investment-service"));

        assertFalse(SystemGraphTools.keepLocal(toOtherArea, mine, Set.of()));
        assertTrue(SystemGraphTools.keepLocal(inside, mine, Set.of()));
        assertFalse(SystemGraphTools.keepLocal(issue("schema", Map.of("topic", "account-opened")), mine, Set.of()));
        assertTrue(SystemGraphTools.involves(toOtherArea, mine, Set.of()));
        assertTrue(SystemGraphTools.involves(issue("schema", Map.of("topic", "account-opened", "producer", "account-service", "consumer", "loan-service")), mine, Set.of()));
        assertFalse(SystemGraphTools.involves(issue("schema", Map.of("topic", "account-opened", "producer", "account-service", "consumer", "customer-service")), mine, Set.of("account-opened")));
        assertFalse(SystemGraphTools.involves(issue("http", Map.of("caller", "payment-service", "target", "account-service")), mine, Set.of()));
        Map<String, Object> inbound = issue("runtime", Map.of("caller", "payment-service", "target", "loan-service"));
        assertFalse(SystemGraphTools.keepLocal(inbound, mine, Set.of("payment-service")));
        assertTrue(SystemGraphTools.keepLocal(inbound, mine, Set.of()));
        assertTrue(SystemGraphTools.involves(inbound, mine, Set.of()));
    }

    private static Map<String, Object> service(String name, String area, String team) {
        return Map.of("name", name, "area", area, "team", team, "indexed", true);
    }

    private static Map<String, Object> issue(String area, Map<String, Object> details) {
        return Map.of("severity", "error", "area", area, "message", area + details, "details", details);
    }
}
