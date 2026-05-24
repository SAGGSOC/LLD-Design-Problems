package moviebooking.enums;

public enum SeatStatus {
    AVAILABLE,    // can be held
    HELD,         // temporarily reserved during checkout (e.g., 5 min timeout)
    BOOKED,       // confirmed booking
    BLOCKED       // taken offline for maintenance
}
