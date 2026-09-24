package com.example.graph.extractor.runtime;

import com.example.graph.extractor.ingest.Neo4jSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.TopicPartition;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

public final class KafkaRuntimeInspector {

    public record Observation(String group, String topic, int activeMembers, String state) {
    }

    public List<Observation> inspect(String bootstrapServers) throws ExecutionException, InterruptedException {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        List<Observation> observations = new ArrayList<>();
        try (Admin admin = Admin.create(config)) {
            List<String> groups = admin.listConsumerGroups().all().get().stream()
                    .map(ConsumerGroupListing::groupId)
                    .filter(id -> !id.startsWith("_") && !id.startsWith("anonymous."))
                    .toList();
            if (groups.isEmpty()) {
                return observations;
            }
            Map<String, ConsumerGroupDescription> descriptions = admin.describeConsumerGroups(groups).all().get();
            for (String group : groups) {
                ConsumerGroupDescription description = descriptions.get(group);
                Set<String> topics = new TreeSet<>();
                Map<String, Integer> membersByTopic = new HashMap<>();
                for (MemberDescription member : description.members()) {
                    member.assignment().topicPartitions().stream()
                            .map(TopicPartition::topic)
                            .distinct()
                            .forEach(topic -> {
                                topics.add(topic);
                                membersByTopic.merge(topic, 1, Integer::sum);
                            });
                }
                admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get().keySet()
                        .forEach(partition -> topics.add(partition.topic()));
                for (String topic : topics) {
                    if (!topic.startsWith("__")) {
                        observations.add(new Observation(group, topic, membersByTopic.getOrDefault(topic, 0), description.state().toString()));
                    }
                }
            }
        }
        return observations;
    }

    public void write(Neo4jSettings settings, List<Observation> observations) {
        List<Map<String, Object>> rows = observations.stream()
                .map(o -> Map.<String, Object>of("group", o.group(), "topic", o.topic(), "members", o.activeMembers(), "state", o.state()))
                .toList();
        try (Driver driver = GraphDatabase.driver(settings.uri(), AuthTokens.basic(settings.user(), settings.password()));
             Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("MATCH ()-[r:OBSERVED_CONSUMING]->() DELETE r");
                tx.run("""
                        UNWIND $rows AS row
                        MERGE (s:Service {name: row.group})
                        ON CREATE SET s.indexed = false
                        MERGE (t:Topic {name: row.topic})
                        MERGE (s)-[o:OBSERVED_CONSUMING]->(t)
                        SET o.activeMembers = row.members,
                            o.state = row.state,
                            o.observedAt = datetime()
                        """, Map.of("rows", rows));
            });
        }
    }
}
