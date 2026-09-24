package com.example.graph.extractor.scan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class SourceScanner {

    private static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "delete", "patch", "head", "options");
    private static final Set<String> HTTP_CLIENT_TYPES = Set.of("RestClient", "WebClient");

    public record ClientCall(String clientField, String method, String path, BaseUrl baseUrl, String location) {
    }

    public record BaseUrl(String raw, String resolved, String propertyKey) {
    }

    public record TemplateSend(String topic, String payloadType, String location) {
    }

    public record BridgeSend(String binding, String payloadType, String location) {
    }

    public record Result(List<ClientCall> httpCalls, List<TemplateSend> kafkaSends, List<BridgeSend> bridgeSends) {
    }

    private record Unit(CompilationUnit cu, Path file) {
    }

    private final Path projectDir;
    private final SpringProperties properties;
    private final ClassIndex classIndex;
    private final Map<String, String> constants = new HashMap<>();
    private final List<Unit> units = new ArrayList<>();

    public SourceScanner(Path projectDir, SpringProperties properties, ClassIndex classIndex) {
        this.projectDir = projectDir;
        this.properties = properties;
        this.classIndex = classIndex;
    }

    public Result scan() throws IOException {
        Path sources = projectDir.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            return new Result(List.of(), List.of(), List.of());
        }
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(new CombinedTypeSolver(
                        new ReflectionTypeSolver(), new JavaParserTypeSolver(sources)))));
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                parser.parse(file).getResult().ifPresent(cu -> units.add(new Unit(cu, file)));
            }
        }
        units.forEach(unit -> indexConstants(unit.cu()));

        List<ClientCall> httpCalls = new ArrayList<>();
        List<TemplateSend> kafkaSends = new ArrayList<>();
        List<BridgeSend> bridgeSends = new ArrayList<>();
        for (Unit unit : units) {
            for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
                switch (call.getNameAsString()) {
                    case "uri" -> httpCall(unit, call).ifPresent(httpCalls::add);
                    case "send" -> {
                        Optional<String> fieldType = rootField(call.getScope().orElse(null)).flatMap(f -> fieldType(call, f)).map(this::rawName);
                        if (fieldType.filter(t -> t.equals("KafkaTemplate") || t.equals("KafkaOperations")).isPresent()) {
                            kafkaSend(unit, call).ifPresent(kafkaSends::add);
                        } else if (fieldType.filter("StreamBridge"::equals).isPresent()) {
                            bridgeSend(unit, call).ifPresent(bridgeSends::add);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return new Result(httpCalls, kafkaSends, bridgeSends);
    }

    private void indexConstants(CompilationUnit cu) {
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            for (FieldDeclaration field : type.getFields()) {
                if (!field.isStatic() || !field.isFinal()) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    variable.getInitializer()
                            .flatMap(this::literal)
                            .ifPresent(value -> {
                                constants.put(type.getNameAsString() + "." + variable.getNameAsString(), value);
                                constants.putIfAbsent(variable.getNameAsString(), value);
                            });
                }
            }
        }
    }

    private Optional<ClientCall> httpCall(Unit unit, MethodCallExpr uriCall) {
        if (uriCall.getScope().isEmpty() || !uriCall.getScope().get().isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr verbCall = uriCall.getScope().get().asMethodCallExpr();
        String verb = verbCall.getNameAsString();
        String httpMethod;
        if (HTTP_VERBS.contains(verb)) {
            httpMethod = verb.toUpperCase();
        } else if (verb.equals("method") && verbCall.getArguments().size() == 1) {
            Expression arg = verbCall.getArgument(0);
            httpMethod = arg.isFieldAccessExpr() ? arg.asFieldAccessExpr().getNameAsString() : arg.toString();
        } else {
            return Optional.empty();
        }
        Optional<String> clientField = rootField(verbCall.getScope().orElse(null));
        if (clientField.isEmpty() || fieldType(uriCall, clientField.get()).map(this::rawName).filter(HTTP_CLIENT_TYPES::contains).isEmpty()) {
            return Optional.empty();
        }
        String path = null;
        if (uriCall.getArguments().isNonEmpty()) {
            Expression first = uriCall.getArgument(0);
            if (first.isLambdaExpr()) {
                path = first.findAll(MethodCallExpr.class).stream()
                        .filter(m -> m.getNameAsString().equals("path") && m.getArguments().isNonEmpty())
                        .findFirst()
                        .flatMap(m -> evaluate(m.getArgument(0)))
                        .orElse(null);
            } else {
                path = evaluate(first).orElse(null);
            }
        }
        BaseUrl baseUrl = baseUrlFor(uriCall, clientField.get()).orElse(null);
        return Optional.of(new ClientCall(clientField.get(), httpMethod, path, baseUrl, location(unit, uriCall)));
    }

    private Optional<TemplateSend> kafkaSend(Unit unit, MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> topic = evaluate(call.getArgument(0));
        if (topic.isEmpty()) {
            return Optional.empty();
        }
        String payload = call.getArguments().size() > 1 ? typeOf(unit, call.getArgument(call.getArguments().size() - 1)).orElse(null) : null;
        if (payload == null) {
            payload = rootField(call.getScope().orElse(null))
                    .flatMap(f -> fieldDeclaredType(call, f))
                    .filter(Type::isClassOrInterfaceType)
                    .flatMap(t -> t.asClassOrInterfaceType().getTypeArguments())
                    .filter(args -> args.size() == 2)
                    .map(args -> qualify(unit, args.get(1).asString()))
                    .orElse(null);
        }
        return Optional.of(new TemplateSend(topic.get(), payload, location(unit, call)));
    }

    private Optional<BridgeSend> bridgeSend(Unit unit, MethodCallExpr call) {
        if (call.getArguments().size() < 2) {
            return Optional.empty();
        }
        return evaluate(call.getArgument(0))
                .map(binding -> new BridgeSend(binding, typeOf(unit, call.getArgument(1)).orElse(null), location(unit, call)));
    }

    private Optional<BaseUrl> baseUrlFor(Node context, String field) {
        Optional<TypeDeclaration> owner = context.findAncestor(TypeDeclaration.class);
        if (owner.isPresent()) {
            for (AssignExpr assign : owner.get().findAll(AssignExpr.class)) {
                if (rootField(assign.getTarget()).filter(field::equals).isPresent()) {
                    Optional<BaseUrl> found = baseUrlIn(assign.getValue());
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
            for (FieldDeclaration declaration : ((TypeDeclaration<?>) owner.get()).getFields()) {
                for (VariableDeclarator variable : declaration.getVariables()) {
                    if (variable.getNameAsString().equals(field) && variable.getInitializer().isPresent()) {
                        Optional<BaseUrl> found = baseUrlIn(variable.getInitializer().get());
                        if (found.isPresent()) {
                            return found;
                        }
                    }
                }
            }
        }
        for (Unit unit : units) {
            for (MethodDeclaration method : unit.cu().findAll(MethodDeclaration.class)) {
                if (method.isAnnotationPresent("Bean")
                        && method.getNameAsString().equals(field)
                        && HTTP_CLIENT_TYPES.contains(rawName(method.getType().asString()))) {
                    Optional<BaseUrl> found = baseUrlIn(method);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<BaseUrl> baseUrlIn(Node node) {
        List<MethodCallExpr> calls = new ArrayList<>(node.findAll(MethodCallExpr.class));
        if (node instanceof MethodCallExpr self) {
            calls.addFirst(self);
        }
        for (MethodCallExpr call : calls) {
            boolean builder = call.getNameAsString().equals("baseUrl");
            boolean factory = call.getNameAsString().equals("create")
                    && call.getScope().map(s -> HTTP_CLIENT_TYPES.contains(s.toString())).orElse(false);
            if ((builder || factory) && call.getArguments().size() == 1) {
                Expression arg = call.getArgument(0);
                Optional<String> raw = rawValue(arg);
                if (raw.isPresent()) {
                    return Optional.of(new BaseUrl(raw.get(), properties.resolve(raw.get()), SpringProperties.placeholderKey(raw.get()).orElse(null)));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> evaluate(Expression expression) {
        return rawValue(expression).map(properties::resolve);
    }

    private Optional<String> rawValue(Expression expression) {
        Optional<String> literal = literal(expression);
        if (literal.isPresent()) {
            return literal;
        }
        if (expression.isNameExpr()) {
            String name = expression.asNameExpr().getNameAsString();
            Optional<String> fromParameter = expression.findAncestor(CallableDeclaration.class)
                    .flatMap(callable -> ((CallableDeclaration<?>) callable).getParameterByName(name))
                    .flatMap(this::valueAnnotation);
            if (fromParameter.isPresent()) {
                return fromParameter;
            }
            Optional<String> fromField = fieldDeclaration(expression, name).flatMap(this::valueAnnotation);
            if (fromField.isPresent()) {
                return fromField;
            }
            return expression.findAncestor(TypeDeclaration.class)
                    .map(t -> constants.get(((TypeDeclaration<?>) t).getNameAsString() + "." + name))
                    .or(() -> Optional.ofNullable(constants.get(name)));
        }
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr access = expression.asFieldAccessExpr();
            if (access.getScope().isThisExpr()) {
                return fieldDeclaration(expression, access.getNameAsString()).flatMap(this::valueAnnotation);
            }
            String scope = access.getScope().toString();
            String simpleScope = scope.substring(scope.lastIndexOf('.') + 1);
            return Optional.ofNullable(constants.get(simpleScope + "." + access.getNameAsString()));
        }
        if (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = rawValue(expression.asBinaryExpr().getLeft());
            Optional<String> right = rawValue(expression.asBinaryExpr().getRight());
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

    private Optional<String> valueAnnotation(Parameter parameter) {
        return parameter.getAnnotationByName("Value").flatMap(this::annotationValue);
    }

    private Optional<String> valueAnnotation(FieldDeclaration field) {
        return field.getAnnotationByName("Value").flatMap(this::annotationValue);
    }

    private Optional<String> annotationValue(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return literal(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst()
                    .flatMap(p -> literal(p.getValue()));
        }
        return Optional.empty();
    }

    private Optional<String> typeOf(Unit unit, Expression expression) {
        try {
            ResolvedType resolved = expression.calculateResolvedType();
            if (resolved.isReferenceType()) {
                return Optional.of(resolved.asReferenceType().getQualifiedName());
            }
        } catch (RuntimeException | StackOverflowError ignored) {
        }
        if (expression.isObjectCreationExpr()) {
            return Optional.of(qualify(unit, expression.asObjectCreationExpr().getType().getNameAsString()));
        }
        if (expression.isNameExpr()) {
            String name = expression.asNameExpr().getNameAsString();
            Optional<CallableDeclaration> callable = expression.findAncestor(CallableDeclaration.class);
            if (callable.isPresent()) {
                for (VariableDeclarator variable : ((Node) callable.get()).findAll(VariableDeclarator.class)) {
                    if (variable.getNameAsString().equals(name)) {
                        if (variable.getType().isVarType() && variable.getInitializer().filter(Expression::isObjectCreationExpr).isPresent()) {
                            return Optional.of(qualify(unit, variable.getInitializer().get().asObjectCreationExpr().getType().getNameAsString()));
                        }
                        return Optional.of(qualify(unit, rawName(variable.getType().asString())));
                    }
                }
                Optional<Parameter> parameter = ((CallableDeclaration<?>) callable.get()).getParameterByName(name);
                if (parameter.isPresent()) {
                    return Optional.of(qualify(unit, rawName(parameter.get().getType().asString())));
                }
            }
        }
        return Optional.empty();
    }

    private String qualify(Unit unit, String simpleName) {
        String raw = rawName(simpleName);
        for (var imported : unit.cu().getImports()) {
            if (!imported.isAsterisk() && imported.getNameAsString().endsWith("." + raw)) {
                return imported.getNameAsString();
            }
        }
        String samePackage = unit.cu().getPackageDeclaration().map(p -> p.getNameAsString() + "." + raw).orElse(raw);
        if (classIndex.contains(samePackage)) {
            return samePackage;
        }
        return classIndex.uniqueBySimpleName(raw).orElse(raw);
    }

    private Optional<String> rootField(Expression expression) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        if (expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()) {
            return Optional.of(expression.asFieldAccessExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private Optional<FieldDeclaration> fieldDeclaration(Node context, String name) {
        return context.findAncestor(TypeDeclaration.class)
                .flatMap(type -> ((TypeDeclaration<?>) type).getFieldByName(name));
    }

    private Optional<Type> fieldDeclaredType(Node context, String name) {
        return fieldDeclaration(context, name)
                .flatMap(field -> field.getVariables().stream()
                        .filter(v -> v.getNameAsString().equals(name))
                        .findFirst())
                .map(VariableDeclarator::getType);
    }

    private Optional<String> fieldType(Node context, String name) {
        return fieldDeclaredType(context, name).map(type -> type instanceof ClassOrInterfaceType c ? c.getNameWithScope() : type.asString());
    }

    private String rawName(String type) {
        int generic = type.indexOf('<');
        String raw = generic >= 0 ? type.substring(0, generic) : type;
        return raw.substring(raw.lastIndexOf('.') + 1).trim();
    }

    private String location(Unit unit, Node node) {
        int line = node.getBegin().map(p -> p.line).orElse(0);
        return projectDir.relativize(unit.file()) + ":" + line;
    }
}
