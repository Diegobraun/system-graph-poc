package demo;

import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.web.client.RestClient;

public class LedgerClient {

    private final HttpSyncGraphQlClient graphQlClient;

    public LedgerClient(RestClient.Builder builder) {
        this.graphQlClient = HttpSyncGraphQlClient.builder(builder.build())
                .url("${services.ledger.url}/graphql")
                .build();
    }

    public Object balance(String account) {
        return graphQlClient.document("{ balance(account: \"x\") }").executeSync();
    }
}
