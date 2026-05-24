package airline.model;

import airline.enums.FareClass;
import airline.enums.FlightStatus;

import java.time.Instant;

public class Flight {
    private final String flightNumber;        // e.g. "DL-2305"
    private final String airline;              // "Delta"
    private final Airport origin;
    private final Airport destination;
    private final Instant scheduledDeparture;
    private final Instant scheduledArrival;
    private final Aircraft aircraft;
    private final FlightInventory inventory;
    private FlightStatus status;

    public Flight(String flightNumber, String airline, Airport origin, Airport destination,
                  Instant scheduledDeparture, Instant scheduledArrival,
                  Aircraft aircraft, FlightInventory inventory) {
        this.flightNumber = flightNumber;
        this.airline = airline;
        this.origin = origin;
        this.destination = destination;
        this.scheduledDeparture = scheduledDeparture;
        this.scheduledArrival = scheduledArrival;
        this.aircraft = aircraft;
        this.inventory = inventory;
        this.status = FlightStatus.SCHEDULED;
    }

    public boolean isBookable() {
        return status == FlightStatus.SCHEDULED
            && Instant.now().isBefore(scheduledDeparture);
    }

    public boolean canCheckIn() {
        // Check-in opens 24h before departure, closes at boarding
        Instant checkInOpens = scheduledDeparture.minusSeconds(24 * 60 * 60);
        Instant now = Instant.now();
        return status == FlightStatus.SCHEDULED
            && !now.isBefore(checkInOpens)
            && now.isBefore(scheduledDeparture);
    }

    public double getFare(FareClass fareClass)                    { return inventory.getFare(fareClass); }
    public int getAvailableSeats(FareClass fareClass)             { return inventory.getAvailableCount(fareClass); }
    public boolean reserveSeat(FareClass fareClass)               { return inventory.reserveSeat(fareClass); }
    public void releaseSeat(FareClass fareClass)                  { inventory.releaseSeat(fareClass); }

    public synchronized void setStatus(FlightStatus status)       { this.status = status; }

    public String getFlightNumber()        { return flightNumber; }
    public String getAirline()             { return airline; }
    public Airport getOrigin()             { return origin; }
    public Airport getDestination()        { return destination; }
    public Instant getScheduledDeparture() { return scheduledDeparture; }
    public Instant getScheduledArrival()   { return scheduledArrival; }
    public Aircraft getAircraft()          { return aircraft; }
    public FlightStatus getStatus()        { return status; }

    @Override
    public String toString() {
        return airline + " " + flightNumber + " " + origin.getCode()
            + "→" + destination.getCode();
    }
}
