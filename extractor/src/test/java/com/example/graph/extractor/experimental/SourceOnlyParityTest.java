package com.example.graph.extractor.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.graph.extractor.experimental.source.SourceOnlyAnnotationSource;
import com.example.graph.extractor.model.ServiceGraph;
import com.example.graph.extractor.scan.Extractor;
import com.example.graph.extractor.support.FixtureProject;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceOnlyParityTest {

    @TempDir
    Path project;

    @ParameterizedTest
    @ValueSource(strings = {"http-clients", "graphql", "messaging", "maven-multi-module", "gradle"})
    void extractsTheSameContractsWithoutCompiledClasses(String fixture) throws Exception {
        Path root = FixtureProject.prepare("fixtures/" + fixture, project);

        ServiceGraph fromBytecode = new Extractor().extract(root);
        ServiceGraph fromSource = new Extractor(new SourceOnlyAnnotationSource()).extract(root);

        assertEquals(fromBytecode.service(), fromSource.service());
        assertEquals(exposes(fromBytecode), exposes(fromSource));
        assertEquals(Set.copyOf(fromBytecode.calls()), Set.copyOf(fromSource.calls()));
        assertEquals(Set.copyOf(fromBytecode.publishes()), Set.copyOf(fromSource.publishes()));
        assertEquals(Set.copyOf(fromBytecode.consumes()), Set.copyOf(fromSource.consumes()));
        assertEquals(Set.copyOf(fromBytecode.schemas()), Set.copyOf(fromSource.schemas()));
    }

    private static Set<String> exposes(ServiceGraph graph) {
        return graph.exposes().stream()
                .map(e -> e.method() + " " + e.path() + " " + e.handler().replaceAll(":\\d+$", ""))
                .collect(Collectors.toSet());
    }
}
