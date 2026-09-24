package com.example.loan.loan;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanService service;
    private final LoanRepository repository;

    public LoanController(LoanService service, LoanRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Loan request(@RequestBody @Valid LoanRequest request) {
        return service.request(request.accountId(), request.amount(), request.installments());
    }

    @GetMapping
    public List<Loan> byAccount(@RequestParam Long accountId) {
        return repository.findByAccount(accountId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Loan> get(@PathVariable String id) {
        return ResponseEntity.of(repository.findById(id));
    }
}
