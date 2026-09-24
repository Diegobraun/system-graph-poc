package com.example.graph.extractor.experimental.observed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DynatraceClient {

    private static final String FIRST_PAGE = "/api/v2/entities?entitySelector=%s&fields=%s&pageSize=500";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI baseUrl;
    private final String token;
    private final HttpClient http;

    public DynatraceClient(URI baseUrl, String token, HttpClient http) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = http;
    }

    public List<ObservedCall> serviceCalls() throws IOException, InterruptedException {
        Map<String, String> names = new HashMap<>();
        List<String[]> edges = new ArrayList<>();
        String path = FIRST_PAGE.formatted(encode("type(\"SERVICE\")"), encode("fromRelationships.calls"));
        while (path != null) {
            JsonNode page = get(path);
            for (JsonNode entity : page.path("entities")) {
                String id = entity.path("entityId").asText();
                names.put(id, entity.path("displayName").asText(id));
                for (JsonNode target : entity.path("fromRelationships").path("calls")) {
                    edges.add(new String[]{id, target.path("id").asText()});
                }
            }
            String next = page.path("nextPageKey").asText(null);
            path = next == null || next.isBlank() ? null : "/api/v2/entities?nextPageKey=" + encode(next);
        }
        List<ObservedCall> calls = new ArrayList<>();
        for (String[] edge : edges) {
            if (names.containsKey(edge[1])) {
                calls.add(new ObservedCall(names.get(edge[0]), names.get(edge[1]), null));
            }
        }
        return calls;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path))
                .header("Authorization", "Api-Token " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Dynatrace returned " + response.statusCode() + " for " + path + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
