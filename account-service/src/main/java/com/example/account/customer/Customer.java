package com.example.account.customer;

import java.math.BigDecimal;
import java.time.Instant;

public record Customer(Long id, String name, String document, BigDecimal monthlyIncome, Instant createdAt) {
}
