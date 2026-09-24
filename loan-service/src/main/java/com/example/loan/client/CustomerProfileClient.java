package com.example.loan.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CustomerProfileClient {

    private final HttpSyncGraphQlClient graphQlClient;

    public CustomerProfileClient(RestClient.Builder builder, @Value("${services.account-service.url}") String baseUrl) {
        this.graphQlClient = HttpSyncGraphQlClient.builder(builder.build())
                .url(baseUrl + "/graphql")
                .build();
    }

    public CustomerProfile profile(Long customerId) {
        return graphQlClient.documentName("customerProfile")
                .variable("id", customerId)
                .retrieveSync("customer")
                .toEntity(CustomerProfile.class);
    }
}
