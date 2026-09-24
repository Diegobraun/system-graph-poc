package com.example.loan.analysis;

import com.example.loan.client.CustomerProfile;
import com.example.loan.client.CustomerProfileClient;
import java.math.BigDecimal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/credit-analysis")
public class CreditAnalysisController {

    private static final BigDecimal INCOME_MULTIPLIER = BigDecimal.valueOf(5);

    private final CustomerProfileClient profileClient;

    public CreditAnalysisController(CustomerProfileClient profileClient) {
        this.profileClient = profileClient;
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CreditAnalysis> analyze(@PathVariable Long customerId) {
        CustomerProfile profile = profileClient.profile(customerId);
        if (profile == null) {
            return ResponseEntity.notFound().build();
        }
        var active = profile.accounts().stream().filter(account -> "ACTIVE".equals(account.status())).toList();
        BigDecimal totalBalance = active.stream().map(CustomerProfile.AccountBalance::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return ResponseEntity.ok(new CreditAnalysis(customerId, profile.name(), profile.monthlyIncome(), totalBalance,
                active.size(), profile.monthlyIncome().multiply(INCOME_MULTIPLIER)));
    }
}
