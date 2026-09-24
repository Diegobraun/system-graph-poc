package demo.messaging;

import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Functions {

    @Bean
    Consumer<InvoiceRequested> invoiceRequested() {
        return request -> {
        };
    }

    @Bean
    Function<RiskRequest, RiskScore> riskScored() {
        return request -> new RiskScore(request.customerId(), 700);
    }
}
