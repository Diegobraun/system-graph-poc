package com.example.account.account;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;
    private final AccountRepository repository;

    public AccountController(AccountService service, AccountRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account open(@RequestBody @Valid OpenAccountRequest request) {
        return service.open(request.customerId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> get(@PathVariable Long id) {
        return ResponseEntity.of(repository.findById(id));
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<AccountSummary> summary(@PathVariable Long id) {
        return ResponseEntity.of(service.summary(id));
    }
}
