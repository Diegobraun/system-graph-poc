package com.example.account.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CreateCustomerRequest(@NotBlank String name, @NotBlank String document, @NotNull @PositiveOrZero BigDecimal monthlyIncome) {
}
