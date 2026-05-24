package airline.enums;

public enum FlightStatus {
    SCHEDULED,   // upcoming flight, open for booking
    BOARDING,    // gate open, check-in closed
    DEPARTED,    // wheels up
    LANDED,      // arrived at destination
    CANCELLED    // flight cancelled by airline
}
