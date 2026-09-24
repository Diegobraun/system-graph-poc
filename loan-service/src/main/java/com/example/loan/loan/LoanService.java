package com.example.loan.loan;

import com.example.loan.client.AccountClient;
import com.example.loan.client.AccountResponse;
import com.example.loan.client.CustomerResponse;
import com.example.loan.messaging.LoanEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LoanService {

    private static final BigDecimal INCOME_MULTIPLIER = BigDecimal.valueOf(5);

    private final AccountClient accountClient;
    private final LoanRepository repository;
    private final LoanEventPublisher publisher;

    public LoanService(AccountClient accountClient, LoanRepository repository, LoanEventPublisher publisher) {
        this.accountClient = accountClient;
        this.repository = repository;
        this.publisher = publisher;
    }

    public Loan request(Long accountId, BigDecimal amount, int installments) {
        AccountResponse account = accountClient.getAccount(accountId);
        CustomerResponse customer = accountClient.getCustomer(account.customerId());
        String id = "LN-" + UUID.randomUUID().toString().substring(0, 8);

        if (!"ACTIVE".equals(account.status())) {
            return repository.save(rejected(id, account, amount, installments, "account is not active"));
        }
        BigDecimal maxAmount = customer.monthlyIncome().multiply(INCOME_MULTIPLIER);
        if (amount.compareTo(maxAmount) > 0) {
            return repository.save(rejected(id, account, amount, installments, "amount exceeds " + maxAmount));
        }

        Loan loan = repository.save(new Loan(id, accountId, account.customerId(), amount, installments, LoanStatus.DISBURSED, null, Instant.now()));
        publisher.loanDisbursed(loan);
        return loan;
    }

    private Loan rejected(String id, AccountResponse account, BigDecimal amount, int installments, String reason) {
        return new Loan(id, account.id(), account.customerId(), amount, installments, LoanStatus.REJECTED, reason, Instant.now());
    }
}
