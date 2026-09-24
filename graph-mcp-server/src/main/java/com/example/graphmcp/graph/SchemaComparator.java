package com.example.graphmcp.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SchemaComparator {

    public record SchemaView(String service, String side, String className, List<String> fieldNames, List<String> fieldTypes) {

        Map<String, String> fields() {
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < fieldNames.size(); i++) {
                fields.put(fieldNames.get(i), i < fieldTypes.size() ? fieldTypes.get(i) : "?");
            }
            return fields;
        }
    }

    public record SchemaIssue(String severity, String topic, String producer, String consumer, String field, String problem) {
    }

    private SchemaComparator() {
    }

    public static List<SchemaIssue> compare(String topic, List<SchemaView> schemas) {
        List<SchemaView> producers = schemas.stream().filter(s -> "producer".equals(s.side())).toList();
        List<SchemaView> consumers = schemas.stream().filter(s -> "consumer".equals(s.side())).toList();
        List<SchemaIssue> issues = new ArrayList<>();
        if (producers.isEmpty() && !consumers.isEmpty()) {
            consumers.forEach(c -> issues.add(new SchemaIssue("warning", topic, null, c.service(), null,
                    "no producer schema known for this topic")));
        }
        for (SchemaView consumer : consumers) {
            Map<String, String> consumed = consumer.fields();
            for (SchemaView producer : producers) {
                Map<String, String> produced = producer.fields();
                consumed.forEach((field, type) -> {
                    if (!produced.containsKey(field)) {
                        issues.add(new SchemaIssue("warning", topic, producer.service(), consumer.service(), field,
                                "%s expects '%s' (%s) but %s never sends it, so it is always null".formatted(
                                        consumer.className(), field, type, producer.className())));
                    } else if (!produced.get(field).equals(type)) {
                        issues.add(new SchemaIssue("error", topic, producer.service(), consumer.service(), field,
                                "type mismatch on '%s': producer sends %s, consumer expects %s".formatted(
                                        field, produced.get(field), type)));
                    }
                });
                produced.keySet().stream()
                        .filter(field -> !consumed.containsKey(field))
                        .forEach(field -> issues.add(new SchemaIssue("info", topic, producer.service(), consumer.service(), field,
                                "'%s' is sent but ignored by %s".formatted(field, consumer.service()))));
            }
        }
        return issues;
    }
}
