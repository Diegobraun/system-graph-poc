package com.example.loan.messaging;

import java.math.BigDecimal;

public record AccountOpenedEvent(Long accountId, Long customerId, BigDecimal monthlyIncome) {
}
