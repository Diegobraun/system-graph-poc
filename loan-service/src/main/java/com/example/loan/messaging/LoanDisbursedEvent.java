package com.example.loan.messaging;

import java.math.BigDecimal;
import java.time.Instant;

public record LoanDisbursedEvent(String loanId, Long accountId, BigDecimal amount, Instant disbursedAt) {
}
