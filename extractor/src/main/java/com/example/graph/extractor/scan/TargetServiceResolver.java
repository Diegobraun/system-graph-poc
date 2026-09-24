package com.example.graph.extractor.scan;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TargetServiceResolver {

    private static final Pattern KEY_CONVENTION = Pattern.compile("(?:^|\\.)([a-z0-9-]+)\\.(?:url|base-url|uri|host)$");
    private static final Pattern IP = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private TargetServiceResolver() {
    }

    record Target(String service, String basePath, String confidence) {
    }

    static Optional<Target> resolve(SourceScanner.BaseUrl baseUrl) {
        if (baseUrl == null || baseUrl.resolved() == null) {
            return Optional.empty();
        }
        String host = null;
        String basePath = "";
        try {
            URI uri = URI.create(baseUrl.resolved());
            host = uri.getHost();
            basePath = uri.getPath() == null ? "" : uri.getPath();
        } catch (IllegalArgumentException ignored) {
        }
        if (host != null && !host.equals("localhost") && !IP.matcher(host).matches()) {
            return Optional.of(new Target(host.split("\\.")[0], basePath, "high"));
        }
        if (baseUrl.propertyKey() != null) {
            Matcher matcher = KEY_CONVENTION.matcher(baseUrl.propertyKey());
            if (matcher.find()) {
                return Optional.of(new Target(matcher.group(1), basePath, "medium"));
            }
        }
        return Optional.empty();
    }
}
