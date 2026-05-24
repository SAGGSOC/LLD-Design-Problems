package airline.enums;

public enum BookingStatus {
    CONFIRMED,    // paid, seat reserved, ticket issued
    CHECKED_IN,   // passenger checked in, specific seat assigned
    CANCELLED,    // cancelled + refunded
    COMPLETED     // flight landed
}
