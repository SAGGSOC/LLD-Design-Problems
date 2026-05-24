package locker.model;

import locker.enums.LockerSize;
import locker.enums.LockerStatus;
import locker.enums.PackageSize;
import locker.exception.LockerException;

public class Locker {
    private static final int MAX_FAILED_ATTEMPTS = 3;

    private final String lockerId;
    private final String locationId;
    private final LockerSize size;

    private LockerStatus status;
    private String currentReservationId;
    private int failedUnlockAttempts;

    public Locker(String lockerId, String locationId, LockerSize size) {
        this.lockerId = lockerId;
        this.locationId = locationId;
        this.size = size;
        this.status = LockerStatus.AVAILABLE;
    }

    public synchronized void reserve(String reservationId) {
        if (status != LockerStatus.AVAILABLE) {
            throw new LockerException("Locker " + lockerId + " not available (status=" + status + ")");
        }
        this.status = LockerStatus.RESERVED;
        this.currentReservationId = reservationId;
    }

    public synchronized void markDeposited() {
        if (status != LockerStatus.RESERVED) {
            throw new LockerException("Cannot deposit — locker not RESERVED");
        }
        this.status = LockerStatus.OCCUPIED;
    }

    public synchronized void markRetrieved() {
        if (status != LockerStatus.OCCUPIED) {
            throw new LockerException("Cannot retrieve — locker not OCCUPIED");
        }
        this.status = LockerStatus.AVAILABLE;
        this.currentReservationId = null;
        this.failedUnlockAttempts = 0;
    }

    public synchronized void releaseReservation() {
        // Used for cancellation before deposit
        if (status == LockerStatus.RESERVED) {
            this.status = LockerStatus.AVAILABLE;
            this.currentReservationId = null;
        }
    }

    public synchronized boolean recordFailedAttempt() {
        failedUnlockAttempts++;
        if (failedUnlockAttempts >= MAX_FAILED_ATTEMPTS) {
            this.status = LockerStatus.MAINTENANCE;
            return true;  // locked out
        }
        return false;
    }

    public synchronized int getRemainingAttempts() {
        return Math.max(0, MAX_FAILED_ATTEMPTS - failedUnlockAttempts);
    }

    public boolean isAvailable() {
        return status == LockerStatus.AVAILABLE;
    }

    /** Locker must be at least as big as the package. */
    public boolean canFit(PackageSize packageSize) {
        return size.ordinal() >= packageSize.ordinal();
    }

    public String getLockerId()             { return lockerId; }
    public String getLocationId()           { return locationId; }
    public LockerSize getSize()             { return size; }
    public LockerStatus getStatus()         { return status; }
    public String getCurrentReservationId() { return currentReservationId; }
}
