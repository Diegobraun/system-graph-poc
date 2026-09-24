package demo.messaging;

import org.springframework.kafka.annotation.KafkaListener;

public class PaymentListener {

    @KafkaListener(topics = "${app.topics.payments}")
    public void onPayment(PaymentSettled event) {
    }
}
