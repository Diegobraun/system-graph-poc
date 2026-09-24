package com.example.graph.extractor.scan;

import com.example.graph.extractor.model.ExposedEndpoint;
import com.example.graph.extractor.model.SchemaField;
import com.example.graph.extractor.model.Subscription;
import io.github.classgraph.AnnotationEnumValue;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationParameterValue;
import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.BaseTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeArgument;
import io.github.classgraph.TypeSignature;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AnnotationScanner {

    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final Map<String, String> MAPPINGS = Map.of(
            WEB + "GetMapping", "GET",
            WEB + "PostMapping", "POST",
            WEB + "PutMapping", "PUT",
            WEB + "DeleteMapping", "DELETE",
            WEB + "PatchMapping", "PATCH",
            WEB + "RequestMapping", "ANY");
    private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";
    private static final String BEAN = "org.springframework.context.annotation.Bean";
    private static final Set<String> WRAPPER_TYPES = Set.of(
            "org.apache.kafka.clients.consumer.ConsumerRecord",
            "org.springframework.messaging.Message",
            "java.util.List");
    private static final Set<String> NON_PAYLOAD_PARAMS = Set.of(
            "org.springframework.messaging.handler.annotation.Header",
            "org.springframework.messaging.handler.annotation.Headers");
    private static final Map<String, String> FUNCTION_KINDS = Map.of(
            "java.util.function.Consumer", "consumer",
            "java.util.function.Function", "function",
            "java.util.function.Supplier", "supplier");

    public record FunctionBean(String name, String kind, String inputType, String outputType, String location) {
    }

    public record Result(
            ClassIndex classIndex,
            List<ExposedEndpoint> exposes,
            List<Subscription> kafkaListeners,
            Map<String, FunctionBean> functionBeans) {
    }

    private final SpringProperties properties;

    public AnnotationScanner(SpringProperties properties) {
        this.properties = properties;
    }

    public Result scan(Path classesDir) {
        ClassIndex index = new ClassIndex();
        List<ExposedEndpoint> exposes = new ArrayList<>();
        List<Subscription> listeners = new ArrayList<>();
        Map<String, FunctionBean> functions = new LinkedHashMap<>();

        try (ScanResult scan = new ClassGraph()
                .overrideClasspath(classesDir.toString())
                .enableAllInfo()
                .scan()) {
            for (ClassInfo type : scan.getAllClasses()) {
                index.add(type.getName(), fieldsOf(type));
                if (type.hasAnnotation(WEB + "RestController") || type.hasAnnotation("org.springframework.stereotype.Controller")) {
                    exposes.addAll(endpointsOf(type));
                }
                for (MethodInfo method : type.getDeclaredMethodInfo()) {
                    AnnotationInfo listener = method.getAnnotationInfo(KAFKA_LISTENER);
                    if (listener != null) {
                        listeners.addAll(subscriptionsOf(type, method, listener));
                    }
                    if (method.hasAnnotation(BEAN)) {
                        functionBean(type, method).ifPresent(bean -> functions.put(bean.name(), bean));
                    }
                }
            }
        }
        return new Result(index, exposes, listeners, functions);
    }

    private List<SchemaField> fieldsOf(ClassInfo type) {
        List<SchemaField> fields = new ArrayList<>();
        for (FieldInfo field : type.getDeclaredFieldInfo()) {
            if (!field.isStatic() && !field.isSynthetic()) {
                fields.add(new SchemaField(field.getName(), field.getTypeSignatureOrTypeDescriptor().toStringWithSimpleNames()));
            }
        }
        return fields;
    }

    private List<ExposedEndpoint> endpointsOf(ClassInfo type) {
        List<String> basePaths = List.of("");
        AnnotationInfo classMapping = type.getAnnotationInfo(WEB + "RequestMapping");
        if (classMapping != null) {
            List<String> paths = strings(classMapping, "value", "path");
            if (!paths.isEmpty()) {
                basePaths = paths;
            }
        }
        List<ExposedEndpoint> endpoints = new ArrayList<>();
        for (MethodInfo method : type.getDeclaredMethodInfo()) {
            for (Map.Entry<String, String> mapping : MAPPINGS.entrySet()) {
                AnnotationInfo annotation = method.getAnnotationInfo(mapping.getKey());
                if (annotation == null) {
                    continue;
                }
                List<String> httpMethods = List.of(mapping.getValue());
                if ("ANY".equals(mapping.getValue())) {
                    List<String> declared = strings(annotation, "method");
                    if (!declared.isEmpty()) {
                        httpMethods = declared;
                    }
                }
                List<String> paths = strings(annotation, "value", "path");
                if (paths.isEmpty()) {
                    paths = List.of("");
                }
                for (String base : basePaths) {
                    for (String path : paths) {
                        for (String httpMethod : httpMethods) {
                            endpoints.add(new ExposedEndpoint(httpMethod, joinPath(base, path), handler(type, method)));
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    private List<Subscription> subscriptionsOf(ClassInfo type, MethodInfo method, AnnotationInfo listener) {
        List<String> groups = strings(listener, "groupId");
        String group = groups.isEmpty()
                ? properties.get("spring.kafka.consumer.group-id").orElse(null)
                : groups.getFirst();
        String payload = payloadTypeOf(method);
        List<Subscription> result = new ArrayList<>();
        for (String topic : strings(listener, "topics")) {
            for (String resolved : properties.resolve(topic).split(",")) {
                result.add(new Subscription(resolved.trim(), "kafka-listener", group, payload, "static", handler(type, method)));
            }
        }
        return result;
    }

    private String payloadTypeOf(MethodInfo method) {
        for (MethodParameterInfo parameter : method.getParameterInfo()) {
            boolean header = NON_PAYLOAD_PARAMS.stream().anyMatch(parameter::hasAnnotation);
            String type = unwrap(parameter.getTypeSignatureOrTypeDescriptor());
            if (!header && type != null && !type.startsWith("org.springframework.kafka.support.")) {
                return type;
            }
        }
        return null;
    }

    private Optional<FunctionBean> functionBean(ClassInfo type, MethodInfo method) {
        TypeSignature result = method.getTypeSignatureOrTypeDescriptor().getResultType();
        if (!(result instanceof ClassRefTypeSignature ref) || !FUNCTION_KINDS.containsKey(ref.getFullyQualifiedClassName())) {
            return Optional.empty();
        }
        String kind = FUNCTION_KINDS.get(ref.getFullyQualifiedClassName());
        List<TypeArgument> args = ref.getTypeArguments();
        String input = null;
        String output = null;
        if ("consumer".equals(kind) && !args.isEmpty()) {
            input = unwrap(args.get(0).getTypeSignature());
        } else if ("function".equals(kind) && args.size() == 2) {
            input = unwrap(args.get(0).getTypeSignature());
            output = unwrap(args.get(1).getTypeSignature());
        } else if ("supplier".equals(kind) && !args.isEmpty()) {
            output = unwrap(args.get(0).getTypeSignature());
        }
        return Optional.of(new FunctionBean(method.getName(), kind, input, output, handler(type, method)));
    }

    private static String unwrap(TypeSignature signature) {
        if (signature instanceof ClassRefTypeSignature ref) {
            List<TypeArgument> args = ref.getTypeArguments();
            if (WRAPPER_TYPES.contains(ref.getFullyQualifiedClassName()) && !args.isEmpty()) {
                return unwrap(args.getLast().getTypeSignature());
            }
            return ref.getFullyQualifiedClassName();
        }
        if (signature instanceof BaseTypeSignature base) {
            return base.getTypeStr();
        }
        if (signature instanceof ArrayTypeSignature array) {
            return array.getElementTypeSignature().toString() + "[]";
        }
        return signature == null ? null : signature.toString();
    }

    private static List<String> strings(AnnotationInfo annotation, String... names) {
        for (String name : names) {
            for (AnnotationParameterValue parameter : annotation.getParameterValues()) {
                if (!parameter.getName().equals(name)) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                Object value = parameter.getValue();
                if (value != null && value.getClass().isArray()) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        values.add(asString(Array.get(value, i)));
                    }
                } else if (value != null) {
                    values.add(asString(value));
                }
                values.removeIf(String::isBlank);
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return List.of();
    }

    private static String asString(Object value) {
        return value instanceof AnnotationEnumValue enumValue ? enumValue.getValueName() : String.valueOf(value);
    }

    private String joinPath(String base, String path) {
        String joined = ("/" + properties.resolve(base) + "/" + properties.resolve(path)).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private static String handler(ClassInfo type, MethodInfo method) {
        return type.getName() + "#" + method.getName() + ":" + method.getMinLineNum();
    }
}
