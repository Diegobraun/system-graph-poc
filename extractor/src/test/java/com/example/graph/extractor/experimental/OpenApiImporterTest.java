package com.example.graph.extractor.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.graph.extractor.experimental.openapi.OpenApiImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiImporterTest {

    @Test
    void readsOpenApi3YamlWithServerPath() throws Exception {
        String spec = """
                openapi: 3.0.1
                servers:
                  - url: https://cards.internal/api/v2
                paths:
                  /cards/{id}:
                    get:
                      operationId: getCard
                    parameters: []
                  /cards:
                    post: {}
                """;

        assertEquals(List.of(
                new OpenApiImporter.Operation("GET", "/api/v2/cards/{id}", "getCard"),
                new OpenApiImporter.Operation("POST", "/api/v2/cards", null)), OpenApiImporter.parse(spec));
    }

    @Test
    void readsSwagger2Json() throws Exception {
        String spec = """
                {"swagger": "2.0", "basePath": "/legacy/",
                 "paths": {"/clients/{doc}": {"delete": {"operationId": "remove"}}}}
                """;

        assertEquals(List.of(new OpenApiImporter.Operation("DELETE", "/legacy/clients/{doc}", "remove")),
                OpenApiImporter.parse(spec));
    }
}
