package parkinglot.model;

import parkinglot.enums.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

public class Ticket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final List<ParkingSpot> spots;  // always a list — 1 for car, 3 for bus, N for anything
    private final Instant entryTime;
    private final String entryGateId;
    private Instant exitTime;
    private TicketStatus status;

    // Single-spot constructor (motorcycle, car, truck)
    public Ticket(String ticketId, Vehicle vehicle, ParkingSpot spot,
                  Instant entryTime, String gateId) {
        this(ticketId, vehicle, Collections.singletonList(spot), entryTime, gateId);
    }

    // Multi-spot constructor (bus, or any future multi-spot vehicle)
    public Ticket(String ticketId, Vehicle vehicle, List<ParkingSpot> spots,
                  Instant entryTime, String gateId) {
        if (spots == null || spots.isEmpty()) {
            throw new IllegalArgumentException("Ticket must have at least one spot");
        }
        this.ticketId = ticketId;
        this.vehicle = vehicle;
        this.spots = Collections.unmodifiableList(spots);
        this.entryTime = entryTime;
        this.entryGateId = gateId;
        this.status = TicketStatus.ACTIVE;
    }

    public Duration getDuration() {
        Instant end = exitTime != null ? exitTime : Instant.now();
        return Duration.between(entryTime, end);
    }

    public String getTicketId()            { return ticketId; }
    public Vehicle getVehicle()            { return vehicle; }
    public ParkingSpot getSpot()           { return spots.get(0); }  // primary spot (for display)
    public List<ParkingSpot> getSpots()    { return spots; }
    public int getSpotCount()              { return spots.size(); }
    public Instant getEntryTime()          { return entryTime; }
    public String getEntryGateId()         { return entryGateId; }
    public Instant getExitTime()           { return exitTime; }
    public TicketStatus getStatus()        { return status; }

    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }
    public void setStatus(TicketStatus status) { this.status = status; }
}
