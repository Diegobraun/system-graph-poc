package com.example.loan.client;

import java.math.BigDecimal;

public record AccountResponse(Long id, Long customerId, BigDecimal balance, String status) {
}
