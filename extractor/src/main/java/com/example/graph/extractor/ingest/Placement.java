package com.example.graph.extractor.ingest;

public record Placement(String area, String team) {

    public static final Placement NONE = new Placement(null, null);
}
