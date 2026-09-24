package demo.messaging;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.core.KafkaTemplate;

public class EventPublisher {

    private final KafkaTemplate<String, OrderPlaced> kafkaTemplate;
    private final StreamBridge streamBridge;

    public EventPublisher(KafkaTemplate<String, OrderPlaced> kafkaTemplate, StreamBridge streamBridge) {
        this.kafkaTemplate = kafkaTemplate;
        this.streamBridge = streamBridge;
    }

    public void orderPlaced(Long orderId, String customerId) {
        OrderPlaced event = new OrderPlaced(orderId, customerId);
        kafkaTemplate.send(Topics.ORDERS, orderId.toString(), event);
        streamBridge.send("audit-out-0", new AuditEntry("order-placed", customerId));
    }
}
