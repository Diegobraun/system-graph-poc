package com.example.loan.loan;

import java.math.BigDecimal;
import java.time.Instant;

public record Loan(String id, Long accountId, Long customerId, BigDecimal amount, int installments,
                   LoanStatus status, String reason, Instant createdAt) {
}
