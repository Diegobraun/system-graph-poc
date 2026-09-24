package com.example.account.account;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final Map<Long, Account> accounts = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    public Long nextId() {
        return sequence.incrementAndGet();
    }

    public Account save(Account account) {
        accounts.put(account.id(), account);
        return account;
    }

    public Optional<Account> findById(Long id) {
        return Optional.ofNullable(accounts.get(id));
    }
}
