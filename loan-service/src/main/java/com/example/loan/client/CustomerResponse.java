package com.example.loan.client;

import java.math.BigDecimal;

public record CustomerResponse(Long id, String name, BigDecimal monthlyIncome) {
}
