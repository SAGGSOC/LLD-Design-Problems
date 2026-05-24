package moviebooking.model;

import moviebooking.enums.PaymentStatus;
import java.time.Instant;

public class Payment {
    private final String paymentId;
    private final String bookingId;
    private final double amount;
    private final PaymentStatus status;
    private final String gatewayReference;
    private final Instant processedAt;

    public Payment(String paymentId, String bookingId, double amount,
                   PaymentStatus status, String gatewayReference) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.status = status;
        this.gatewayReference = gatewayReference;
        this.processedAt = Instant.now();
    }

    public String getPaymentId()         { return paymentId; }
    public String getBookingId()         { return bookingId; }
    public double getAmount()            { return amount; }
    public PaymentStatus getStatus()     { return status; }
    public String getGatewayReference()  { return gatewayReference; }
    public Instant getProcessedAt()      { return processedAt; }
}
