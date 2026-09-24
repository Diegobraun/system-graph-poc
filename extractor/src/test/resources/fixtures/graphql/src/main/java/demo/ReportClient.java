package demo;

import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.web.client.RestClient;

public class ReportClient {

    private static final String DASHBOARD = """
            query dashboard {
                reports { id }
                kpis { value }
            }
            """;

    private final HttpSyncGraphQlClient graphQlClient = HttpSyncGraphQlClient.builder(RestClient.create("http://report-service:8080"))
            .url("/graphql")
            .build();

    public Object dashboard() {
        return graphQlClient.document(DASHBOARD).executeSync();
    }

    public Object closeDay(String date) {
        return graphQlClient.documentName("closeDay").variable("date", date).executeSync();
    }
}
