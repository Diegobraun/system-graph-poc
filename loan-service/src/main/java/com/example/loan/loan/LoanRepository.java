package com.example.loan.loan;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class LoanRepository {

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();

    public Loan save(Loan loan) {
        loans.put(loan.id(), loan);
        return loan;
    }

    public Optional<Loan> findById(String id) {
        return Optional.ofNullable(loans.get(id));
    }
}
