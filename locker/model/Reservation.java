package locker.model;

import locker.enums.ReservationStatus;
import java.time.Instant;

public class Reservation {
    private final String reservationId;
    private final String orderId;
    private final String customerId;
    private final String lockerId;
    private final String packageId;
    private final Instant createdAt;

    private ReservationStatus status;
    private Instant depositedAt;
    private Instant retrievedAt;
    private Instant expiresAt;

    public Reservation(String reservationId, String orderId, String customerId,
                       String lockerId, String packageId) {
        this.reservationId = reservationId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.lockerId = lockerId;
        this.packageId = packageId;
        this.createdAt = Instant.now();
        this.status = ReservationStatus.CREATED;
    }

    public String getReservationId()       { return reservationId; }
    public String getOrderId()             { return orderId; }
    public String getCustomerId()          { return customerId; }
    public String getLockerId()            { return lockerId; }
    public String getPackageId()           { return packageId; }
    public ReservationStatus getStatus()   { return status; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getDepositedAt()        { return depositedAt; }
    public Instant getRetrievedAt()        { return retrievedAt; }
    public Instant getExpiresAt()          { return expiresAt; }

    public void setStatus(ReservationStatus status)   { this.status = status; }
    public void setDepositedAt(Instant depositedAt)   { this.depositedAt = depositedAt; }
    public void setRetrievedAt(Instant retrievedAt)   { this.retrievedAt = retrievedAt; }
    public void setExpiresAt(Instant expiresAt)       { this.expiresAt = expiresAt; }
}
