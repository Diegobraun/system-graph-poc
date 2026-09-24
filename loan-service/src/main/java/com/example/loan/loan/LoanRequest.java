package com.example.loan.loan;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record LoanRequest(@NotNull Long accountId, @NotNull @Positive BigDecimal amount, @Min(1) @Max(72) int installments) {
}
