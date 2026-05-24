package airline.model;

import airline.enums.PaymentStatus;

import java.time.Instant;

public class Payment {
    private final String paymentId;
    private final String bookingId;
    private final double amount;
    private final PaymentStatus status;
    private final String gatewayRef;
    private final Instant processedAt;

    public Payment(String paymentId, String bookingId, double amount,
                   PaymentStatus status, String gatewayRef) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.status = status;
        this.gatewayRef = gatewayRef;
        this.processedAt = Instant.now();
    }

    public String getPaymentId()       { return paymentId; }
    public String getBookingId()       { return bookingId; }
    public double getAmount()          { return amount; }
    public PaymentStatus getStatus()   { return status; }
    public String getGatewayRef()      { return gatewayRef; }
    public Instant getProcessedAt()    { return processedAt; }
}
