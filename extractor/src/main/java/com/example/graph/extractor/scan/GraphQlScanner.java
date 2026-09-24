package com.example.graph.extractor.scan;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.OperationDefinition;
import graphql.language.OperationTypeDefinition;
import graphql.parser.Parser;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class GraphQlScanner {

    public record Operation(String type, String field) {
    }

    public record Schema(String sdl, List<Operation> operations) {
    }

    private GraphQlScanner() {
    }

    public static Optional<Schema> loadSchema(Path resourcesDir) throws IOException {
        Path schemaDir = resourcesDir.resolve("graphql");
        if (!Files.isDirectory(schemaDir)) {
            return Optional.empty();
        }
        StringBuilder sdl = new StringBuilder();
        try (Stream<Path> files = Files.walk(schemaDir)) {
            for (Path file : files.filter(GraphQlScanner::isSchemaFile).sorted().toList()) {
                sdl.append(Files.readString(file)).append('\n');
            }
        }
        if (sdl.isEmpty()) {
            return Optional.empty();
        }
        TypeDefinitionRegistry registry = new SchemaParser().parse(sdl.toString());
        List<Operation> operations = new ArrayList<>();
        rootTypes(registry).forEach((operation, typeName) -> {
            List<FieldDefinition> fields = new ArrayList<>();
            registry.getType(typeName, ObjectTypeDefinition.class).ifPresent(type -> fields.addAll(type.getFieldDefinitions()));
            for (ObjectTypeExtensionDefinition extension : registry.objectTypeExtensions().getOrDefault(typeName, List.of())) {
                fields.addAll(extension.getFieldDefinitions());
            }
            fields.forEach(field -> operations.add(new Operation(operation, field.getName())));
        });
        return Optional.of(new Schema(sdl.toString().trim(), operations));
    }

    public static Optional<String> loadDocument(Path resourcesDir, String name) throws IOException {
        for (String extension : List.of(".graphql", ".gql")) {
            Path file = resourcesDir.resolve("graphql-documents").resolve(name + extension);
            if (Files.exists(file)) {
                return Optional.of(Files.readString(file));
            }
        }
        return Optional.empty();
    }

    public static List<Operation> rootOperations(String document) {
        Document parsed = new Parser().parseDocument(document);
        List<Operation> operations = new ArrayList<>();
        for (OperationDefinition definition : parsed.getDefinitionsOfType(OperationDefinition.class)) {
            String type = definition.getOperation().name();
            definition.getSelectionSet().getSelectionsOfType(Field.class)
                    .forEach(field -> operations.add(new Operation(type, field.getName())));
        }
        return operations;
    }

    private static Map<String, String> rootTypes(TypeDefinitionRegistry registry) {
        Map<String, String> roots = new LinkedHashMap<>();
        registry.schemaDefinition().ifPresentOrElse(
                schema -> {
                    for (OperationTypeDefinition definition : schema.getOperationTypeDefinitions()) {
                        roots.put(definition.getName().toUpperCase(Locale.ROOT), definition.getTypeName().getName());
                    }
                },
                () -> {
                    roots.put("QUERY", "Query");
                    roots.put("MUTATION", "Mutation");
                    roots.put("SUBSCRIPTION", "Subscription");
                });
        return roots;
    }

    private static boolean isSchemaFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".graphqls") || name.endsWith(".gqls");
    }
}
