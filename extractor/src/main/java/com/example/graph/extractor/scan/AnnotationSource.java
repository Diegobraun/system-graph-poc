package com.example.graph.extractor.scan;

import java.io.IOException;

@FunctionalInterface
public interface AnnotationSource {

    AnnotationScanner.Result scan(ProjectLayout layout, SpringProperties properties) throws IOException;
}
