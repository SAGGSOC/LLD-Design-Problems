package locker.enums;

public enum LockerStatus {
    AVAILABLE,    // free, can be reserved
    RESERVED,     // reserved but package not yet deposited
    OCCUPIED,     // package deposited, awaiting pickup
    MAINTENANCE   // hardware issue or security lockout
}
