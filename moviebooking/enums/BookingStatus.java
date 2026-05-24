package moviebooking.enums;

public enum BookingStatus {
    PENDING,     // seats held, awaiting payment
    CONFIRMED,   // payment successful, seats booked
    CANCELLED,   // user cancelled
    EXPIRED      // payment timed out, seats released
}
