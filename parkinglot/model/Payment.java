package parkinglot.model;

import parkinglot.enums.VehicleType;
import java.time.Duration;
import java.time.Instant;

public class Payment {
    private final String paymentId;
    private final String ticketId;
    private final double amount;
    private final Duration duration;
    private final VehicleType vehicleType;
    private final Instant paidAt;

    public Payment(String ticketId, double amount, Duration duration,
                   VehicleType vehicleType) {
        this.paymentId = "PAY-" + System.currentTimeMillis();
        this.ticketId = ticketId;
        this.amount = amount;
        this.duration = duration;
        this.vehicleType = vehicleType;
        this.paidAt = Instant.now();
    }

    public String getPaymentId()      { return paymentId; }
    public String getTicketId()       { return ticketId; }
    public double getAmount()         { return amount; }
    public Duration getDuration()     { return duration; }
    public VehicleType getVehicleType() { return vehicleType; }
    public Instant getPaidAt()        { return paidAt; }

    @Override
    public String toString() {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return String.format("Payment[ticket=%s, amount=$%.2f, duration=%dh %dm, type=%s]",
                ticketId, amount, hours, minutes, vehicleType);
    }
}
