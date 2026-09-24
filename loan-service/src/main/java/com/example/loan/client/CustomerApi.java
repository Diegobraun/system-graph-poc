package com.example.loan.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange("/customers")
public interface CustomerApi {

    @GetExchange("/{id}")
    CustomerResponse get(@PathVariable Long id);
}
