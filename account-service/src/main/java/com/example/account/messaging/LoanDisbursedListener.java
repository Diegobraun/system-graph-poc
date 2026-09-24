package com.example.account.messaging;

import com.example.account.account.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LoanDisbursedListener {

    private static final Logger log = LoggerFactory.getLogger(LoanDisbursedListener.class);

    private final AccountService service;

    public LoanDisbursedListener(AccountService service) {
        this.service = service;
    }

    @KafkaListener(topics = "${app.topics.loan-disbursed}")
    public void onLoanDisbursed(LoanDisbursedEvent event) {
        log.info("Crediting {} to account {} from loan {}", event.amount(), event.accountId(), event.loanId());
        service.credit(event.accountId(), event.amount(), event.loanId());
    }
}
