package demo.messaging;

public record PaymentSettled(String paymentId, java.math.BigDecimal amount) {
}
