package demo.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;

public class RefundListener {

    @KafkaListener(topics = Topics.REFUNDS, groupId = "refunds-team")
    public void onRefund(@Header("traceId") String traceId, ConsumerRecord<String, RefundIssued> record) {
    }
}
