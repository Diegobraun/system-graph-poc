package com.example.account.messaging;

import com.example.account.account.Account;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountEventPublisher {

    private final KafkaTemplate<String, AccountOpenedEvent> kafkaTemplate;

    public AccountEventPublisher(KafkaTemplate<String, AccountOpenedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void accountOpened(Account account) {
        AccountOpenedEvent event = new AccountOpenedEvent(account.id(), account.customerId(), account.openedAt());
        kafkaTemplate.send(Topics.ACCOUNT_OPENED, account.id().toString(), event);
    }
}
