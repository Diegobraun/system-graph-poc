package com.example.graph.extractor.model;

import java.util.List;

public record PayloadSchema(String className, List<SchemaField> fields) {
}
