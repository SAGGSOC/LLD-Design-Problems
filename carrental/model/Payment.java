package carrental.model;

import carrental.enums.PaymentStatus;
import java.time.Instant;

public class Payment {
    private final String paymentId;
    private final String reservationId;
    private final double amount;
    private final PaymentStatus status;
    private final String gatewayRef;
    private final Instant processedAt;

    public Payment(String paymentId, String reservationId, double amount,
                   PaymentStatus status, String gatewayRef) {
        this.paymentId = paymentId;
        this.reservationId = reservationId;
        this.amount = amount;
        this.status = status;
        this.gatewayRef = gatewayRef;
        this.processedAt = Instant.now();
    }

    public String getPaymentId()      { return paymentId; }
    public String getReservationId()  { return reservationId; }
    public double getAmount()         { return amount; }
    public PaymentStatus getStatus()  { return status; }
    public String getGatewayRef()     { return gatewayRef; }
    public Instant getProcessedAt()   { return processedAt; }
}
