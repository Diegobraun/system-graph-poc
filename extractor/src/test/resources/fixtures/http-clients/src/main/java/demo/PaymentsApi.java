package demo;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange(url = "${services.payments.url}/v1")
public interface PaymentsApi {

    @PostExchange("/payments")
    Object pay(@RequestBody Object payment);
}
