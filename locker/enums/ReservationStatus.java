package locker.enums;

public enum ReservationStatus {
    CREATED,     // reservation made, locker locked waiting for deposit
    DEPOSITED,   // package inside, OTP sent to customer
    RETRIEVED,   // customer picked up — terminal state
    EXPIRED,     // uncollected past deadline — terminal state
    CANCELLED    // order cancelled — terminal state
}
