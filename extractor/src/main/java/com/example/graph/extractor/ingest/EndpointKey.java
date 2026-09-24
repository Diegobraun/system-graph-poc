package com.example.graph.extractor.ingest;

public final class EndpointKey {

    private EndpointKey() {
    }

    public static String of(String service, String method, String path) {
        String normalized = path.replaceAll("\\{[^}]*}", "{}").replaceAll("/+", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return service + " " + method + " " + normalized;
    }
}
