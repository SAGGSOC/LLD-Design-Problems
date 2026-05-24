import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ParkingLot — interview-ready, single-file (~170 lines).
 *
 * Scope in 30 minutes:
 *   - Multi-floor lot, 3 vehicle types (BIKE, CAR, TRUCK) → 3 spot sizes (S, M, L)
 *   - Vehicle fits if spot size >= required
 *   - Lock-free spot claim with AtomicBoolean CAS (no ReentrantLock overhead)
 *   - Ticket-based exit with hourly pricing
 *
 * Out of scope: buses (multi-spot), reservations, payment, EV, handicap
 */
public class ParkingLot {

    enum VehicleType { BIKE, CAR, TRUCK }
    enum SpotSize    { SMALL, MEDIUM, LARGE }   // ordinal ordering = fit ordering

    static class Vehicle {
        final String plate;
        final VehicleType type;
        Vehicle(String plate, VehicleType type) { this.plate = plate; this.type = type; }
    }

    static class Spot {
        final String spotId;
        final int floor;
        final SpotSize size;
        final AtomicBoolean occupied = new AtomicBoolean(false);
        volatile Vehicle vehicle;

        Spot(String spotId, int floor, SpotSize size) {
            this.spotId = spotId;
            this.floor = floor;
            this.size = size;
        }

        /** Vehicle fits if spot is at least as large as required. Bike=S, Car=M, Truck=L. */
        boolean canFit(VehicleType type) {
            return size.ordinal() >= type.ordinal();
        }

        /** CAS-based claim. Returns true on success, false if someone else claimed first. */
        boolean tryClaim(Vehicle v) {
            if (!canFit(v.type)) return false;
            if (occupied.compareAndSet(false, true)) {
                this.vehicle = v;
                return true;
            }
            return false;
        }

        void release() {
            this.vehicle = null;
            occupied.set(false);
        }
    }

    static class Ticket {
        final String ticketId;
        final Spot spot;
        final Instant entryTime;
        Instant exitTime;
        double amount;

        Ticket(String ticketId, Spot spot) {
            this.ticketId = ticketId;
            this.spot = spot;
            this.entryTime = Instant.now();
        }
    }

    // ─── Service ───

    static class ParkingService {
        final List<Spot> allSpots;
        final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
        final Map<VehicleType, Double> hourlyRate = Map.of(
            VehicleType.BIKE, 2.0, VehicleType.CAR, 5.0, VehicleType.TRUCK, 10.0);

        ParkingService(List<Spot> spots) {
            // Sort by floor then spotId so "nearest first" is a linear scan
            spots.sort(Comparator.comparingInt((Spot s) -> s.floor)
                .thenComparing(s -> s.spotId));
            this.allSpots = spots;
        }

        /** Park a vehicle: find nearest free spot that fits, claim via CAS, issue ticket. */
        Ticket park(Vehicle vehicle) {
            for (Spot spot : allSpots) {
                if (spot.tryClaim(vehicle)) {
                    String ticketId = "TKT-" + UUID.randomUUID().toString().substring(0, 8);
                    Ticket ticket = new Ticket(ticketId, spot);
                    activeTickets.put(ticketId, ticket);
                    return ticket;
                }
            }
            throw new RuntimeException("No spots available for " + vehicle.type);
        }

        /** Exit: round up to hours, charge by vehicle type, release spot. */
        Ticket exit(String ticketId) {
            Ticket ticket = activeTickets.remove(ticketId);
            if (ticket == null) throw new RuntimeException("Unknown ticket: " + ticketId);
            ticket.exitTime = Instant.now();
            long minutes = Duration.between(ticket.entryTime, ticket.exitTime).toMinutes();
            long hours = (minutes + 59) / 60;  // round up
            if (hours == 0) hours = 1;          // minimum 1 hour
            ticket.amount = hours * hourlyRate.get(ticket.spot.vehicle.type);
            ticket.spot.release();
            return ticket;
        }

        long availableCount(VehicleType type) {
            return allSpots.stream()
                .filter(s -> !s.occupied.get() && s.canFit(type))
                .count();
        }
    }

    // ─── Demo ───

    public static void main(String[] args) throws Exception {
        // Build 2 floors × (2 SMALL + 3 MEDIUM + 1 LARGE) = 12 spots
        List<Spot> spots = new ArrayList<>();
        for (int f = 1; f <= 2; f++) {
            int n = 1;
            for (int i = 0; i < 2; i++) spots.add(new Spot("F" + f + "-" + n++, f, SpotSize.SMALL));
            for (int i = 0; i < 3; i++) spots.add(new Spot("F" + f + "-" + n++, f, SpotSize.MEDIUM));
            spots.add(new Spot("F" + f + "-" + n++, f, SpotSize.LARGE));
        }
        ParkingService service = new ParkingService(spots);

        // Happy path
        Ticket t1 = service.park(new Vehicle("ABC-123", VehicleType.CAR));
        System.out.println("Car parked at " + t1.spot.spotId);

        Thread.sleep(50);
        Ticket done = service.exit(t1.ticketId);
        System.out.println("Exit: $" + done.amount);

        // Bike fits in smallest spot (nearest-first → floor 1 SMALL spot)
        Ticket t2 = service.park(new Vehicle("BIKE-99", VehicleType.BIKE));
        System.out.println("Bike parked at " + t2.spot.spotId + " (size=" + t2.spot.size + ")");

        // Truck only fits in LARGE
        Ticket t3 = service.park(new Vehicle("TRK-1", VehicleType.TRUCK));
        System.out.println("Truck parked at " + t3.spot.spotId + " (size=" + t3.spot.size + ")");

        // Concurrent race: fill all MEDIUM+ spots with 20 cars — only a few should succeed
        System.out.println("\n--- Concurrent race: 20 cars for limited MEDIUM+ spots ---");
        long availableForCar = service.availableCount(VehicleType.CAR);
        System.out.println("Before: " + availableForCar + " spots fit a car");

        int[] successes = {0}, failures = {0};
        Thread[] threads = new Thread[20];
        for (int i = 0; i < 20; i++) {
            String plate = "RACE-" + i;
            threads[i] = new Thread(() -> {
                try {
                    service.park(new Vehicle(plate, VehicleType.CAR));
                    synchronized (successes) { successes[0]++; }
                } catch (Exception e) {
                    synchronized (failures) { failures[0]++; }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("Successes: " + successes[0] + " (expected <= " + availableForCar + ")");
        System.out.println("Failures:  " + failures[0]);
        System.out.println("Remaining: " + service.availableCount(VehicleType.CAR));
    }
}
