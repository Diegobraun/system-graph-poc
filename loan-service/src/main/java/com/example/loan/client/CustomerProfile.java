package com.example.loan.client;

import java.math.BigDecimal;
import java.util.List;

public record CustomerProfile(String name, BigDecimal monthlyIncome, List<AccountBalance> accounts) {

    public record AccountBalance(Long id, BigDecimal balance, String status) {
    }
}
