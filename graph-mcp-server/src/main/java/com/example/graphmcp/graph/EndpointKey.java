package com.example.graphmcp.graph;

public final class EndpointKey {

    private EndpointKey() {
    }

    public static String normalizePath(String path) {
        String normalized = ("/" + path.trim()).replaceAll("\\{[^}]*}", "{}").replaceAll("/+", "/");
        return normalized.length() > 1 && normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
