package com.example.graphmcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

final class Federation {

    private Federation() {
    }

    static List<Map<String, Object>> union(Object local, Object remote, Function<Map<String, Object>, Object> key) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Object> seen = new LinkedHashSet<>();
        for (Map<String, Object> entry : maps(local)) {
            seen.add(key.apply(entry));
            result.add(entry);
        }
        for (Map<String, Object> entry : maps(remote)) {
            if (seen.add(key.apply(entry))) {
                Map<String, Object> copy = new LinkedHashMap<>(entry);
                copy.put("origin", "hub");
                result.add(copy);
            }
        }
        return result;
    }

    static List<Object> unionValues(Object local, Object remote) {
        Set<Object> result = new LinkedHashSet<>();
        if (local instanceof Collection<?> a) {
            result.addAll(a);
        }
        if (remote instanceof Collection<?> b) {
            result.addAll(b);
        }
        return new ArrayList<>(result);
    }

    static List<Map<String, Object>> mergeMatching(Object local, Object remote, Function<Map<String, Object>, Object> key,
                                                   String listField, Function<Map<String, Object>, Object> listKey) {
        Map<Object, Map<String, Object>> remoteByKey = new LinkedHashMap<>();
        maps(remote).forEach(entry -> remoteByKey.put(key.apply(entry), entry));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : maps(local)) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            Map<String, Object> other = remoteByKey.remove(key.apply(entry));
            if (other != null) {
                Object merged = listKey == null
                        ? unionValues(entry.get(listField), other.get(listField))
                        : union(entry.get(listField), other.get(listField), listKey);
                copy.put(listField, merged);
            }
            result.add(copy);
        }
        return result;
    }

    static void annotate(Collection<Map<String, Object>> entries, Map<String, Map<String, Object>> catalog) {
        for (Map<String, Object> entry : entries) {
            Map<String, Object> service = catalog.get(String.valueOf(entry.get("service")));
            if (service == null) {
                continue;
            }
            if (entry.get("area") == null && service.get("area") != null) {
                entry.put("area", service.get("area"));
            }
            if (entry.get("team") == null && service.get("team") != null) {
                entry.put("team", service.get("team"));
            }
        }
    }

    static List<Map<String, Object>> affectedAreas(Collection<String> services, Map<String, Map<String, Object>> catalog, String ownerArea) {
        Map<String, Set<String>> servicesByArea = new TreeMap<>();
        Map<String, Set<String>> teamsByArea = new TreeMap<>();
        for (String service : services) {
            Map<String, Object> entry = catalog.get(service);
            Object area = entry == null ? null : entry.get("area");
            if (area == null || Objects.equals(area, ownerArea)) {
                continue;
            }
            servicesByArea.computeIfAbsent(area.toString(), k -> new TreeSet<>()).add(service);
            if (entry.get("team") != null) {
                teamsByArea.computeIfAbsent(area.toString(), k -> new TreeSet<>()).add(entry.get("team").toString());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        servicesByArea.forEach((area, names) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("area", area);
            row.put("teams", new ArrayList<>(teamsByArea.getOrDefault(area, Set.of())));
            row.put("services", new ArrayList<>(names));
            result.add(row);
        });
        return result;
    }

    static Map<String, Map<String, Object>> byName(List<Map<String, Object>> catalog) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        catalog.forEach(row -> result.put(String.valueOf(row.get("name")), row));
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }
}
