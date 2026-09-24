package com.example.graph.extractor.experimental.source;

import com.example.graph.extractor.model.ExposedEndpoint;
import com.example.graph.extractor.model.SchemaField;
import com.example.graph.extractor.model.Subscription;
import com.example.graph.extractor.scan.AnnotationScanner;
import com.example.graph.extractor.scan.AnnotationSource;
import com.example.graph.extractor.scan.ClassIndex;
import com.example.graph.extractor.scan.ProjectLayout;
import com.example.graph.extractor.scan.SpringProperties;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class SourceOnlyAnnotationSource implements AnnotationSource {

    private static final Map<String, String> MAPPINGS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "ANY");
    private static final Map<String, String> EXCHANGES = Map.of(
            "GetExchange", "GET", "PostExchange", "POST", "PutExchange", "PUT",
            "DeleteExchange", "DELETE", "PatchExchange", "PATCH", "HttpExchange", "ANY");
    private static final Map<String, String> GRAPHQL_MAPPINGS = Map.of(
            "QueryMapping", "QUERY", "MutationMapping", "MUTATION", "SubscriptionMapping", "SUBSCRIPTION");
    private static final Map<String, String> GRAPHQL_ROOT_TYPES = Map.of(
            "Query", "QUERY", "Mutation", "MUTATION", "Subscription", "SUBSCRIPTION");
    private static final Map<String, String> FUNCTION_KINDS = Map.of(
            "Consumer", "consumer", "Function", "function", "Supplier", "supplier");
    private static final Set<String> WRAPPER_TYPES = Set.of("ConsumerRecord", "Message", "List");
    private static final Set<String> HEADER_ANNOTATIONS = Set.of("Header", "Headers");

    private record Unit(CompilationUnit cu, TypeDeclaration<?> type, String className) {
    }

    private record Mapping(String httpMethod, String path) {
    }

    private final Map<String, String> constants = new HashMap<>();
    private final ClassIndex classIndex = new ClassIndex();
    private SpringProperties properties;

    @Override
    public AnnotationScanner.Result scan(ProjectLayout layout, SpringProperties properties) throws IOException {
        this.properties = properties;
        List<Unit> units = parse(layout);
        units.forEach(this::indexConstants);
        units.forEach(unit -> classIndex.add(unit.className(), fieldsOf(unit.type())));

        List<ExposedEndpoint> exposes = new ArrayList<>();
        List<Subscription> listeners = new ArrayList<>();
        Map<String, AnnotationScanner.FunctionBean> functions = new LinkedHashMap<>();
        List<AnnotationScanner.DeclarativeClient> clients = new ArrayList<>();
        Map<String, String> graphqlHandlers = new LinkedHashMap<>();
        for (Unit unit : units) {
            TypeDeclaration<?> type = unit.type();
            if (has(type, "RestController") || has(type, "Controller")) {
                exposes.addAll(endpointsOf(unit));
            }
            if (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) {
                declarativeClient(unit).ifPresent(clients::add);
            }
            for (MethodDeclaration method : type.getMethods()) {
                annotation(method, "KafkaListener").ifPresent(listener -> listeners.addAll(subscriptionsOf(unit, method, listener)));
                graphqlOperation(unit, method).ifPresent(operation -> graphqlHandlers.putIfAbsent(operation, handler(unit, method)));
                if (has(method, "Bean")) {
                    functionBean(unit, method).ifPresent(bean -> functions.put(bean.name(), bean));
                }
            }
        }
        return new AnnotationScanner.Result(classIndex, exposes, listeners, functions, clients, graphqlHandlers);
    }

    private List<Unit> parse(ProjectLayout layout) throws IOException {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        List<Unit> units = new ArrayList<>();
        for (Path sources : layout.sourceDirs()) {
            try (Stream<Path> files = Files.walk(sources)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    parser.parse(file).getResult().ifPresent(cu -> {
                        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                            type.getFullyQualifiedName().ifPresent(name -> units.add(new Unit(cu, type, name)));
                        }
                    });
                }
            }
        }
        return units;
    }

    private void indexConstants(Unit unit) {
        for (FieldDeclaration field : unit.type().getFields()) {
            if (field.isStatic() && field.isFinal()) {
                for (VariableDeclarator variable : field.getVariables()) {
                    variable.getInitializer().flatMap(this::literal).ifPresent(value -> {
                        constants.put(unit.type().getNameAsString() + "." + variable.getNameAsString(), value);
                        constants.put(unit.className() + "#" + variable.getNameAsString(), value);
                    });
                }
            }
        }
    }

    private List<SchemaField> fieldsOf(TypeDeclaration<?> type) {
        List<SchemaField> fields = new ArrayList<>();
        if (type instanceof RecordDeclaration record) {
            record.getParameters().forEach(p -> fields.add(new SchemaField(p.getNameAsString(), simpleType(p.getType()))));
            return fields;
        }
        for (FieldDeclaration field : type.getFields()) {
            if (!field.isStatic()) {
                field.getVariables().forEach(v -> fields.add(new SchemaField(v.getNameAsString(), simpleType(v.getType()))));
            }
        }
        return fields;
    }

    private List<ExposedEndpoint> endpointsOf(Unit unit) {
        List<String> basePaths = annotation(unit.type(), "RequestMapping")
                .map(a -> values(unit, a, "value", "path"))
                .filter(paths -> !paths.isEmpty())
                .orElse(List.of(""));
        List<ExposedEndpoint> endpoints = new ArrayList<>();
        for (MethodDeclaration method : unit.type().getMethods()) {
            for (Mapping mapping : mappingsOf(unit, method, MAPPINGS, List.of("value", "path"))) {
                for (String base : basePaths) {
                    endpoints.add(new ExposedEndpoint(mapping.httpMethod(), joinPath(base, mapping.path()), handler(unit, method)));
                }
            }
        }
        return endpoints;
    }

    private Optional<AnnotationScanner.DeclarativeClient> declarativeClient(Unit unit) {
        Optional<AnnotationExpr> feign = annotation(unit.type(), "FeignClient");
        if (feign.isPresent()) {
            String name = first(unit, feign.get(), "name", "value").map(properties::resolve).orElse(null);
            String url = first(unit, feign.get(), "url").orElse(null);
            String basePath = first(unit, feign.get(), "path").orElse("");
            List<AnnotationScanner.ClientMethod> methods = new ArrayList<>();
            for (MethodDeclaration method : unit.type().getMethods()) {
                for (Mapping mapping : mappingsOf(unit, method, MAPPINGS, List.of("value", "path"))) {
                    methods.add(new AnnotationScanner.ClientMethod(mapping.httpMethod(), joinPath(basePath, mapping.path()), method.getNameAsString()));
                }
            }
            return Optional.of(new AnnotationScanner.DeclarativeClient(unit.className(), "feign", name, url, methods));
        }
        Optional<AnnotationExpr> typeExchange = annotation(unit.type(), "HttpExchange");
        String url = null;
        String basePath = "";
        if (typeExchange.isPresent()) {
            String value = first(unit, typeExchange.get(), "url", "value").orElse("");
            if (properties.resolve(value).contains("://")) {
                url = value;
            } else {
                basePath = value;
            }
        }
        List<AnnotationScanner.ClientMethod> methods = new ArrayList<>();
        for (MethodDeclaration method : unit.type().getMethods()) {
            for (Mapping mapping : mappingsOf(unit, method, EXCHANGES, List.of("url", "value"))) {
                methods.add(new AnnotationScanner.ClientMethod(mapping.httpMethod(), joinPath(basePath, mapping.path()), method.getNameAsString()));
            }
        }
        if (typeExchange.isEmpty() && methods.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AnnotationScanner.DeclarativeClient(unit.className(), "http-exchange", null, url, methods));
    }

    private List<Mapping> mappingsOf(Unit unit, MethodDeclaration method, Map<String, String> annotations, List<String> pathAttributes) {
        List<Mapping> result = new ArrayList<>();
        for (Map.Entry<String, String> mapping : annotations.entrySet()) {
            Optional<AnnotationExpr> annotation = annotation(method, mapping.getKey());
            if (annotation.isEmpty()) {
                continue;
            }
            List<String> httpMethods = List.of(mapping.getValue());
            if ("ANY".equals(mapping.getValue())) {
                List<String> declared = values(unit, annotation.get(), "method");
                if (!declared.isEmpty()) {
                    httpMethods = declared.stream().map(String::toUpperCase).toList();
                }
            }
            List<String> paths = values(unit, annotation.get(), pathAttributes.toArray(String[]::new));
            for (String path : paths.isEmpty() ? List.of("") : paths) {
                httpMethods.forEach(httpMethod -> result.add(new Mapping(httpMethod, path)));
            }
        }
        return result;
    }

    private List<Subscription> subscriptionsOf(Unit unit, MethodDeclaration method, AnnotationExpr listener) {
        String group = first(unit, listener, "groupId")
                .orElseGet(() -> properties.get("spring.kafka.consumer.group-id").orElse(null));
        String payload = payloadTypeOf(unit, method);
        List<Subscription> result = new ArrayList<>();
        for (String topic : values(unit, listener, "topics", "value")) {
            for (String resolved : properties.resolve(topic).split(",")) {
                result.add(new Subscription(resolved.trim(), "kafka-listener", group, payload, "static", handler(unit, method)));
            }
        }
        return result;
    }

    private String payloadTypeOf(Unit unit, MethodDeclaration method) {
        for (Parameter parameter : method.getParameters()) {
            boolean header = parameter.getAnnotations().stream().anyMatch(a -> HEADER_ANNOTATIONS.contains(simpleName(a.getNameAsString())));
            String type = unwrap(unit, parameter.getType());
            if (!header && type != null && !type.startsWith("org.springframework.kafka.support.")) {
                return type;
            }
        }
        return null;
    }

    private Optional<AnnotationScanner.FunctionBean> functionBean(Unit unit, MethodDeclaration method) {
        if (!(method.getType() instanceof ClassOrInterfaceType returned) || !FUNCTION_KINDS.containsKey(returned.getNameAsString())) {
            return Optional.empty();
        }
        String kind = FUNCTION_KINDS.get(returned.getNameAsString());
        NodeList<Type> args = returned.getTypeArguments().orElse(new NodeList<>());
        String input = null;
        String output = null;
        if ("consumer".equals(kind) && !args.isEmpty()) {
            input = unwrap(unit, args.get(0));
        } else if ("function".equals(kind) && args.size() == 2) {
            input = unwrap(unit, args.get(0));
            output = unwrap(unit, args.get(1));
        } else if ("supplier".equals(kind) && !args.isEmpty()) {
            output = unwrap(unit, args.get(0));
        }
        return Optional.of(new AnnotationScanner.FunctionBean(method.getNameAsString(), kind, input, output, handler(unit, method)));
    }

    private Optional<String> graphqlOperation(Unit unit, MethodDeclaration method) {
        for (Map.Entry<String, String> mapping : GRAPHQL_MAPPINGS.entrySet()) {
            Optional<AnnotationExpr> annotation = annotation(method, mapping.getKey());
            if (annotation.isPresent()) {
                return Optional.of(mapping.getValue() + " " + first(unit, annotation.get(), "name", "value").orElse(method.getNameAsString()));
            }
        }
        return annotation(method, "SchemaMapping").flatMap(schemaMapping -> first(unit, schemaMapping, "typeName")
                .map(GRAPHQL_ROOT_TYPES::get)
                .map(operation -> operation + " " + first(unit, schemaMapping, "field", "value").orElse(method.getNameAsString())));
    }

    private String unwrap(Unit unit, Type type) {
        if (type instanceof ClassOrInterfaceType classType) {
            Optional<NodeList<Type>> args = classType.getTypeArguments();
            if (WRAPPER_TYPES.contains(classType.getNameAsString()) && args.isPresent() && !args.get().isEmpty()) {
                return unwrap(unit, args.get().getLast().orElseThrow());
            }
            return qualify(unit, classType.getNameWithScope());
        }
        return type.asString();
    }

    private String qualify(Unit unit, String name) {
        if (name.contains(".") && Character.isLowerCase(name.charAt(0))) {
            return name;
        }
        for (ImportDeclaration imported : unit.cu().getImports()) {
            if (!imported.isAsterisk() && !imported.isStatic() && imported.getNameAsString().endsWith("." + name)) {
                return imported.getNameAsString();
            }
        }
        String samePackage = unit.cu().getPackageDeclaration().map(p -> p.getNameAsString() + "." + name).orElse(name);
        if (classIndex.contains(samePackage)) {
            return samePackage;
        }
        return classIndex.uniqueBySimpleName(name).orElse(name);
    }

    private List<String> values(Unit unit, AnnotationExpr annotation, String... names) {
        for (String name : names) {
            Optional<Expression> value = Optional.empty();
            if (annotation instanceof SingleMemberAnnotationExpr single && name.equals("value")) {
                value = Optional.of(single.getMemberValue());
            } else if (annotation instanceof NormalAnnotationExpr normal) {
                value = normal.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).findFirst().map(p -> p.getValue());
            }
            if (value.isPresent()) {
                List<Expression> items = value.get().isArrayInitializerExpr()
                        ? value.get().asArrayInitializerExpr().getValues()
                        : List.of(value.get());
                List<String> result = items.stream().map(item -> evaluate(unit, item)).flatMap(Optional::stream)
                        .filter(s -> !s.isBlank()).toList();
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return List.of();
    }

    private Optional<String> first(Unit unit, AnnotationExpr annotation, String... names) {
        return values(unit, annotation, names).stream().findFirst();
    }

    private Optional<String> evaluate(Unit unit, Expression expression) {
        Optional<String> literal = literal(expression);
        if (literal.isPresent()) {
            return literal;
        }
        if (expression.isNameExpr()) {
            String name = expression.asNameExpr().getNameAsString();
            return Optional.ofNullable(constants.get(unit.className() + "#" + name));
        }
        if (expression.isFieldAccessExpr()) {
            String scope = simpleName(expression.asFieldAccessExpr().getScope().toString());
            String field = expression.asFieldAccessExpr().getNameAsString();
            String constant = constants.get(scope + "." + field);
            if (constant != null) {
                return Optional.of(constant);
            }
            return scope.equals("RequestMethod") ? Optional.of(field) : Optional.empty();
        }
        if (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = evaluate(unit, expression.asBinaryExpr().getLeft());
            Optional<String> right = evaluate(unit, expression.asBinaryExpr().getRight());
            if (left.isPresent() && right.isPresent()) {
                return Optional.of(left.get() + right.get());
            }
        }
        return Optional.empty();
    }

    private Optional<String> literal(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return Optional.of(expression.asStringLiteralExpr().asString());
        }
        if (expression.isTextBlockLiteralExpr()) {
            return Optional.of(expression.asTextBlockLiteralExpr().asString());
        }
        if (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = literal(expression.asBinaryExpr().getLeft());
            Optional<String> right = literal(expression.asBinaryExpr().getRight());
            if (left.isPresent() && right.isPresent()) {
                return Optional.of(left.get() + right.get());
            }
        }
        return Optional.empty();
    }

    private static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotations().stream().filter(a -> simpleName(a.getNameAsString()).equals(simpleName)).findFirst();
    }

    private static boolean has(NodeWithAnnotations<?> node, String simpleName) {
        return annotation(node, simpleName).isPresent();
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String simpleType(Type type) {
        return type.asString().replaceAll("\\b(?:[a-z_][\\w]*\\.)+([A-Z]\\w*)", "$1");
    }

    private String joinPath(String base, String path) {
        String joined = ("/" + properties.resolve(base) + "/" + properties.resolve(path)).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private static String handler(Unit unit, MethodDeclaration method) {
        return unit.className() + "#" + method.getNameAsString() + ":" + method.getBegin().map(p -> p.line).orElse(0);
    }
}
