package com.example.graph.extractor.model;

import java.util.List;

public record ServiceGraph(
        String service,
        String repository,
        String commitSha,
        String extractedAt,
        List<ExposedEndpoint> exposes,
        List<HttpCall> calls,
        List<Publication> publishes,
        List<Subscription> consumes,
        List<PayloadSchema> schemas,
        String graphqlSchema) {
}
