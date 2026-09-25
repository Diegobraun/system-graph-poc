package com.example.graphmcp;

import com.example.graphmcp.graph.GraphClient;
import com.example.graphmcp.graph.Hub;
import com.example.graphmcp.tools.SystemGraphTools;
import java.time.Duration;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GraphConfig {

    @Bean
    GraphClient graphClient(Driver driver,
                            @Value("${graph.query-timeout:5s}") Duration timeout,
                            @Value("${graph.max-rows:200}") int maxRows) {
        return new GraphClient(driver, timeout, maxRows);
    }

    @Bean(destroyMethod = "close")
    Hub hub(@Value("${graph.hub.uri:}") String uri,
            @Value("${graph.hub.user:neo4j}") String user,
            @Value("${graph.hub.password:password123}") String password,
            @Value("${graph.query-timeout:5s}") Duration timeout,
            @Value("${graph.max-rows:200}") int maxRows) {
        if (uri.isBlank()) {
            return Hub.none();
        }
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        return Hub.of(driver, new GraphClient(driver, timeout, maxRows));
    }

    @Bean
    SystemGraphTools systemGraphTools(GraphClient graph, Hub hub, @Value("${graph.area:}") String area) {
        return new SystemGraphTools(graph, hub, area.isBlank() ? null : area);
    }
}
