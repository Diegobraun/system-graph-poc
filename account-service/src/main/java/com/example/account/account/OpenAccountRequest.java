package com.example.account.account;

import jakarta.validation.constraints.NotNull;

public record OpenAccountRequest(@NotNull Long customerId) {
}
