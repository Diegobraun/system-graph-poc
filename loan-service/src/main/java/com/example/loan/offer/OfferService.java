package com.example.loan.offer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class OfferService {

    private static final BigDecimal PRE_APPROVED_MULTIPLIER = BigDecimal.valueOf(3);

    private final Map<Long, PreApprovedOffer> offers = new ConcurrentHashMap<>();

    public PreApprovedOffer createFor(Long accountId, Long customerId, BigDecimal monthlyIncome) {
        BigDecimal limit = monthlyIncome == null ? BigDecimal.ZERO : monthlyIncome.multiply(PRE_APPROVED_MULTIPLIER);
        PreApprovedOffer offer = new PreApprovedOffer(accountId, customerId, limit, Instant.now());
        offers.put(accountId, offer);
        return offer;
    }

    public Optional<PreApprovedOffer> findByAccount(Long accountId) {
        return Optional.ofNullable(offers.get(accountId));
    }
}
