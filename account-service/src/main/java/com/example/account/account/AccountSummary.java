package com.example.account.account;

import com.example.account.loan.LoanSummary;
import java.math.BigDecimal;
import java.util.List;

public record AccountSummary(Long accountId, BigDecimal balance, BigDecimal totalBorrowed, List<LoanSummary> loans) {
}
