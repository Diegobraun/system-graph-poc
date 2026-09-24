package com.example.loan.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AccountClient {

    private final RestClient restClient;

    public AccountClient(RestClient.Builder builder, @Value("${services.account-service.url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public AccountResponse getAccount(Long accountId) {
        return restClient.get()
                .uri("/accounts/{id}", accountId)
                .retrieve()
                .body(AccountResponse.class);
    }

    public CustomerResponse getCustomer(Long customerId) {
        return restClient.get()
                .uri("/customers/{id}", customerId)
                .retrieve()
                .body(CustomerResponse.class);
    }
}
