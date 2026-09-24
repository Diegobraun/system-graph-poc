package com.example.graph.extractor.model;

public record HttpCall(
        String targetService,
        String method,
        String path,
        String baseUrl,
        String source,
        String confidence,
        String location) {
}
