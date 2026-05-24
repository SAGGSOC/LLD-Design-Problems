package carrental.model;

import carrental.enums.ReservationStatus;

import java.time.Instant;

public class Reservation {
    private final String reservationId;
    private final Customer customer;
    private final Vehicle vehicle;
    private final Store pickupStore;
    private final Store returnStore;
    private final DateRange dateRange;
    private final double estimatedCost;
    private final Instant createdAt;

    private ReservationStatus status;
    private String paymentId;

    public Reservation(String reservationId, Customer customer, Vehicle vehicle,
                       Store pickupStore, Store returnStore, DateRange dateRange,
                       double estimatedCost) {
        this.reservationId = reservationId;
        this.customer = customer;
        this.vehicle = vehicle;
        this.pickupStore = pickupStore;
        this.returnStore = returnStore;
        this.dateRange = dateRange;
        this.estimatedCost = estimatedCost;
        this.createdAt = Instant.now();
        this.status = ReservationStatus.CONFIRMED;
    }

    public String getReservationId()      { return reservationId; }
    public Customer getCustomer()         { return customer; }
    public Vehicle getVehicle()           { return vehicle; }
    public Store getPickupStore()         { return pickupStore; }
    public Store getReturnStore()         { return returnStore; }
    public DateRange getDateRange()       { return dateRange; }
    public double getEstimatedCost()      { return estimatedCost; }
    public Instant getCreatedAt()         { return createdAt; }
    public ReservationStatus getStatus()  { return status; }
    public String getPaymentId()          { return paymentId; }

    public void setStatus(ReservationStatus status)   { this.status = status; }
    public void setPaymentId(String paymentId)        { this.paymentId = paymentId; }
}
