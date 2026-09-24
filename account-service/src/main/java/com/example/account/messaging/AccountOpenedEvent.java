package com.example.account.messaging;

import java.time.Instant;

public record AccountOpenedEvent(Long accountId, Long customerId, Instant openedAt) {
}
