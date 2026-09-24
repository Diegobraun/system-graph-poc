package com.example.graph.extractor.model;

public record Subscription(String topic, String via, String group, String payloadType, String source, String location) {
}
