package com.example.graph.extractor.scan;

import com.example.graph.extractor.model.PayloadSchema;
import com.example.graph.extractor.model.SchemaField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ClassIndex {

    private final Map<String, List<SchemaField>> fieldsByClass = new HashMap<>();
    private final Map<String, String> classBySimpleName = new HashMap<>();
    private final Map<String, Integer> simpleNameCount = new HashMap<>();

    void add(String className, List<SchemaField> fields) {
        fieldsByClass.put(className, fields);
        String simple = simpleName(className);
        classBySimpleName.put(simple, className);
        simpleNameCount.merge(simple, 1, Integer::sum);
    }

    public boolean contains(String className) {
        return fieldsByClass.containsKey(className);
    }

    public Optional<String> uniqueBySimpleName(String simpleName) {
        return simpleNameCount.getOrDefault(simpleName, 0) == 1
                ? Optional.of(classBySimpleName.get(simpleName))
                : Optional.empty();
    }

    public Optional<PayloadSchema> schemaOf(String className) {
        return Optional.ofNullable(fieldsByClass.get(className)).map(fields -> new PayloadSchema(className, fields));
    }

    public static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }
}
