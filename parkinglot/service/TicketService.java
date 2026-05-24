package parkinglot.service;

import parkinglot.enums.TicketStatus;
import parkinglot.exception.TicketAlreadyClosedException;
import parkinglot.exception.TicketNotFoundException;
import parkinglot.model.ParkingSpot;
import parkinglot.model.Ticket;
import parkinglot.model.Vehicle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TicketService {
    private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
    private final Map<String, Ticket> ticketsByPlate = new ConcurrentHashMap<>();
    private final AtomicLong ticketCounter = new AtomicLong(1);

    public Ticket issueTicket(Vehicle vehicle, List<ParkingSpot> spots, String gateId) {
        String ticketId = "TKT-" + String.format("%06d", ticketCounter.getAndIncrement());
        Ticket ticket = new Ticket(ticketId, vehicle, spots, Instant.now(), gateId);
        activeTickets.put(ticketId, ticket);
        ticketsByPlate.put(vehicle.getLicensePlate(), ticket);
        return ticket;
    }

    public Ticket closeTicket(String ticketId) {
        Ticket ticket = activeTickets.get(ticketId);
        if (ticket == null) throw new TicketNotFoundException(ticketId);
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw new TicketAlreadyClosedException(ticketId);
        }

        ticket.setExitTime(Instant.now());
        ticket.setStatus(TicketStatus.PAID);
        activeTickets.remove(ticketId);
        ticketsByPlate.remove(ticket.getVehicle().getLicensePlate());
        return ticket;
    }

    public Ticket getTicket(String ticketId) {
        return activeTickets.get(ticketId);
    }

    public Ticket getTicketByPlate(String licensePlate) {
        return ticketsByPlate.get(licensePlate);
    }

    public int getActiveTicketCount() {
        return activeTickets.size();
    }
}
