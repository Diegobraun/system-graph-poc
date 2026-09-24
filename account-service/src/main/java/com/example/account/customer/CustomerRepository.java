package com.example.account.customer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    private final Map<Long, Customer> customers = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Long nextId() {
        return sequence.incrementAndGet();
    }

    public Customer save(Customer customer) {
        customers.put(customer.id(), customer);
        return customer;
    }

    public Optional<Customer> findById(Long id) {
        return Optional.ofNullable(customers.get(id));
    }
}
