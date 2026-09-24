package com.example.account.graphql;

import com.example.account.account.Account;
import com.example.account.account.AccountRepository;
import com.example.account.customer.Customer;
import com.example.account.customer.CustomerRepository;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public class CustomerGraphController {

    private final CustomerRepository customers;
    private final AccountRepository accounts;

    public CustomerGraphController(CustomerRepository customers, AccountRepository accounts) {
        this.customers = customers;
        this.accounts = accounts;
    }

    @QueryMapping
    public Customer customer(@Argument Long id) {
        return customers.findById(id).orElse(null);
    }

    @QueryMapping
    public Account account(@Argument Long id) {
        return accounts.findById(id).orElse(null);
    }

    @SchemaMapping(typeName = "Customer")
    public List<Account> accounts(Customer customer) {
        return accounts.findByCustomer(customer.id());
    }
}
