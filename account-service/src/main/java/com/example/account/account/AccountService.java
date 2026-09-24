package com.example.account.account;

import com.example.account.customer.CustomerRepository;
import com.example.account.loan.LoanClient;
import com.example.account.loan.LoanSummary;
import com.example.account.messaging.AccountEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final AccountEventPublisher publisher;
    private final LoanClient loanClient;
    private final Set<String> processedCredits = ConcurrentHashMap.newKeySet();

    public AccountService(AccountRepository accounts, CustomerRepository customers, AccountEventPublisher publisher, LoanClient loanClient) {
        this.accounts = accounts;
        this.customers = customers;
        this.publisher = publisher;
        this.loanClient = loanClient;
    }

    public Account open(Long customerId) {
        customers.findById(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "customer not found"));
        Account account = accounts.save(new Account(accounts.nextId(), customerId, BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now()));
        publisher.accountOpened(account);
        return account;
    }

    public void credit(Long accountId, BigDecimal amount, String reference) {
        if (!processedCredits.add(reference)) {
            return;
        }
        accounts.findById(accountId).ifPresent(account -> accounts.save(account.credit(amount)));
    }

    public Optional<AccountSummary> summary(Long accountId) {
        return accounts.findById(accountId).map(account -> {
            List<LoanSummary> loans = loanClient.byAccount(accountId).stream()
                    .filter(loan -> "DISBURSED".equals(loan.status()))
                    .toList();
            BigDecimal borrowed = loans.stream().map(LoanSummary::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new AccountSummary(accountId, account.balance(), borrowed, loans);
        });
    }
}
