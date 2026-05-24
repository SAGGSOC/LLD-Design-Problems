package hotel.enums;

public enum BookingStatus {
    CONFIRMED,     // booked, awaiting check-in
    CHECKED_IN,    // guest is currently in the room
    CHECKED_OUT,   // completed stay
    CANCELLED,     // user or system cancelled
    NO_SHOW        // guest never showed up
}
