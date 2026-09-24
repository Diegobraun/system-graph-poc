package com.example.loan.offer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/offers")
public class OfferController {

    private final OfferService service;

    public OfferController(OfferService service) {
        this.service = service;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<PreApprovedOffer> get(@PathVariable Long accountId) {
        return ResponseEntity.of(service.findByAccount(accountId));
    }
}
