package com.example.graphmcp.graph;

import graphql.ParseAndValidate;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.ValidationError;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public final class GraphQlAnalyzer {

    public record Analysis(List<String> errors, Set<String> fieldsUsed) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private static final Map<String, GraphQLSchema> SCHEMAS = new ConcurrentHashMap<>();

    private GraphQlAnalyzer() {
    }

    public static Analysis analyze(String sdl, String document) {
        GraphQLSchema schema;
        try {
            schema = schema(sdl);
        } catch (RuntimeException e) {
            return new Analysis(List.of("server schema could not be parsed: " + e.getMessage()), Set.of());
        }
        Document parsed;
        try {
            parsed = Parser.parse(document);
        } catch (RuntimeException e) {
            return new Analysis(List.of("client document could not be parsed: " + e.getMessage()), Set.of());
        }
        List<ValidationError> errors = ParseAndValidate.validate(schema, parsed);
        if (!errors.isEmpty()) {
            return new Analysis(errors.stream().map(ValidationError::getDescription).toList(), Set.of());
        }
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        parsed.getDefinitionsOfType(FragmentDefinition.class).forEach(fragment -> fragments.put(fragment.getName(), fragment));
        Set<String> fields = new TreeSet<>();
        for (OperationDefinition operation : parsed.getDefinitionsOfType(OperationDefinition.class)) {
            GraphQLObjectType root = switch (operation.getOperation()) {
                case QUERY -> schema.getQueryType();
                case MUTATION -> schema.getMutationType();
                case SUBSCRIPTION -> schema.getSubscriptionType();
            };
            collect(schema, root, operation.getSelectionSet(), fragments, fields);
        }
        return new Analysis(List.of(), fields);
    }

    private static void collect(GraphQLSchema schema, GraphQLType parent, SelectionSet selections,
                                Map<String, FragmentDefinition> fragments, Set<String> fields) {
        if (selections == null) {
            return;
        }
        for (Selection<?> selection : selections.getSelections()) {
            switch (selection) {
                case Field field when parent instanceof GraphQLFieldsContainer container && !field.getName().startsWith("__") -> {
                    GraphQLFieldDefinition definition = container.getFieldDefinition(field.getName());
                    fields.add(container.getName() + "." + field.getName());
                    if (definition != null) {
                        collect(schema, GraphQLTypeUtil.unwrapAll(definition.getType()), field.getSelectionSet(), fragments, fields);
                    }
                }
                case InlineFragment inline -> collect(schema,
                        inline.getTypeCondition() == null ? parent : schema.getType(inline.getTypeCondition().getName()),
                        inline.getSelectionSet(), fragments, fields);
                case FragmentSpread spread -> {
                    FragmentDefinition fragment = fragments.get(spread.getName());
                    if (fragment != null) {
                        collect(schema, schema.getType(fragment.getTypeCondition().getName()), fragment.getSelectionSet(), fragments, fields);
                    }
                }
                default -> {
                }
            }
        }
    }

    public static boolean hasField(String sdl, String typeAndField) {
        String[] parts = typeAndField.split("\\.", 2);
        return Optional.ofNullable(schema(sdl).getType(parts[0]))
                .filter(GraphQLFieldsContainer.class::isInstance)
                .map(type -> ((GraphQLFieldsContainer) type).getFieldDefinition(parts[1]) != null)
                .orElse(false);
    }

    private static GraphQLSchema schema(String sdl) {
        return SCHEMAS.computeIfAbsent(sdl, text ->
                new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(text), RuntimeWiring.MOCKED_WIRING));
    }
}
