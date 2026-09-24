package com.example.loan.messaging;

import com.example.loan.loan.Loan;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

@Component
public class LoanEventPublisher {

    private static final String LOAN_DISBURSED_BINDING = "loanDisbursed-out-0";

    private final StreamBridge streamBridge;

    public LoanEventPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public void loanDisbursed(Loan loan) {
        LoanDisbursedEvent event = new LoanDisbursedEvent(loan.id(), loan.accountId(), loan.amount(), loan.createdAt());
        streamBridge.send(LOAN_DISBURSED_BINDING, event);
    }
}
