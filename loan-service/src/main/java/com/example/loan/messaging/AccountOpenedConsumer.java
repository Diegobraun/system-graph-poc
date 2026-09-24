package com.example.loan.messaging;

import com.example.loan.offer.OfferService;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountOpenedConsumer {

    @Bean
    Consumer<AccountOpenedEvent> accountOpened(OfferService offerService) {
        return event -> offerService.createFor(event.accountId(), event.customerId(), event.monthlyIncome());
    }
}
