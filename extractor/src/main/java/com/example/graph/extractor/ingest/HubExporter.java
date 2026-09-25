package com.example.graph.extractor.ingest;

import com.example.graph.extractor.model.HttpCall;
import com.example.graph.extractor.model.ServiceGraph;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class HubExporter {

    private final GraphIngestor hub;

    public HubExporter(GraphIngestor hub) {
        this.hub = hub;
    }

    public Set<String> export(String area, Map<ServiceGraph, String> graphsWithTeam) {
        Set<String> areaServices = new TreeSet<>(hub.servicesInArea(area));
        graphsWithTeam.keySet().forEach(graph -> areaServices.add(graph.service()));
        hub.ensureConstraints();
        graphsWithTeam.forEach((graph, team) -> hub.ingest(graph, new Placement(area, team), leavesArea(areaServices)));
        hub.dropCallsInsideArea(area);
        return areaServices;
    }

    static Predicate<HttpCall> leavesArea(Set<String> areaServices) {
        return call -> call.targetService() != null && !areaServices.contains(call.targetService());
    }
}
