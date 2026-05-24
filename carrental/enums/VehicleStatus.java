package carrental.enums;

public enum VehicleStatus {
    AVAILABLE,     // at store, ready to rent
    RESERVED,      // held for an upcoming reservation (optional finer state)
    RENTED,        // currently with a customer
    MAINTENANCE    // being serviced, not rentable
}
