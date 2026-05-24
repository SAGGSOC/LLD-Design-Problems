package airline.service;

import airline.enums.PaymentStatus;
import airline.model.Payment;

import java.util.Random;
import java.util.UUID;

public class PaymentGateway {
    private final Random random = new Random();
    private final double successRate;

    public PaymentGateway() { this(0.95); }
    public PaymentGateway(double successRate) { this.successRate = successRate; }

    public Payment charge(String bookingId, double amount) {
        String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
        PaymentStatus status = random.nextDouble() < successRate
            ? PaymentStatus.COMPLETED : PaymentStatus.FAILED;
        return new Payment(paymentId, bookingId, amount, status,
            "GW-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public Payment refund(String paymentId, double amount) {
        return new Payment(paymentId, null, amount, PaymentStatus.REFUNDED,
            "REFUND-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
