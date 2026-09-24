package com.example.account.loan;

import java.math.BigDecimal;

public record LoanSummary(String id, BigDecimal amount, int installments, String status) {
}
