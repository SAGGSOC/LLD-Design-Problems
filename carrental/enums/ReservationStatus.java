package carrental.enums;

public enum ReservationStatus {
    CONFIRMED,     // reserved, awaiting pickup
    PICKED_UP,     // customer collected the car (now a Rental is active)
    COMPLETED,     // car returned
    CANCELLED,     // user cancelled before pickup
    NO_SHOW        // customer never picked up
}
