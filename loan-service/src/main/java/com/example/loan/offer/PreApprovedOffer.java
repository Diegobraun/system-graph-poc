package com.example.loan.offer;

import java.math.BigDecimal;
import java.time.Instant;

public record PreApprovedOffer(Long accountId, Long customerId, BigDecimal limit, Instant createdAt) {
}
