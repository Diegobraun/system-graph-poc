package com.example.account.account;

import com.example.account.customer.CustomerRepository;
import com.example.account.messaging.AccountEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
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
    private final Set<String> processedCredits = ConcurrentHashMap.newKeySet();

    public AccountService(AccountRepository accounts, CustomerRepository customers, AccountEventPublisher publisher) {
        this.accounts = accounts;
        this.customers = customers;
        this.publisher = publisher;
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
}
