package airline;

import airline.enums.FareClass;
import airline.exception.AirlineException;
import airline.exception.NoSeatsAvailableException;
import airline.model.*;
import airline.service.BookingService;
import airline.service.FlightService;
import airline.service.PaymentGateway;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AirlineDemo {

    public static void main(String[] args) throws Exception {
        // ─── Setup ───
        FlightService flightService = new FlightService();
        BookingService bookingService = new BookingService(new PaymentGateway(1.0));

        // Airports
        Airport sea = new Airport("SEA", "Seattle-Tacoma Intl", "Seattle", "USA", "America/Los_Angeles");
        Airport lax = new Airport("LAX", "Los Angeles Intl", "Los Angeles", "USA", "America/Los_Angeles");
        Airport jfk = new Airport("JFK", "John F. Kennedy Intl", "New York", "USA", "America/New_York");

        // Aircraft
        Map<FareClass, Integer> seatConfig = Map.of(
            FareClass.FIRST, 8,
            FareClass.BUSINESS, 24,
            FareClass.ECONOMY, 150
        );
        Aircraft boeing787 = new Aircraft("AC-001", "Boeing 787-9", seatConfig);

        // Create a flight 2 days from now — within booking window, not in check-in window yet
        Instant departure = Instant.now().plusSeconds(2L * 24 * 60 * 60);
        Instant arrival = departure.plusSeconds(3 * 60 * 60);

        Map<FareClass, Double> fares = Map.of(
            FareClass.FIRST, 1200.00,
            FareClass.BUSINESS, 600.00,
            FareClass.ECONOMY, 250.00
        );
        FlightInventory inventory = new FlightInventory(seatConfig, fares);

        Flight flight1 = new Flight("DL-2305", "Delta", sea, lax,
            departure, arrival, boeing787, inventory);
        flightService.addFlight(flight1);

        // ─── Scenario 1: Search flights ───
        System.out.println("=== Scenario 1: Search flights ===");
        LocalDate searchDate = departure.atZone(ZoneId.systemDefault()).toLocalDate();
        List<Flight> found = flightService.search("SEA", "LAX", searchDate, FareClass.ECONOMY);
        System.out.println("Found " + found.size() + " flight(s):");
        for (Flight f : found) {
            System.out.println("  " + f + " @ " + f.getScheduledDeparture()
                + " — ECONOMY: $" + f.getFare(FareClass.ECONOMY)
                + " (" + f.getAvailableSeats(FareClass.ECONOMY) + " seats)");
        }
        System.out.println();

        // ─── Scenario 2: Book a flight ───
        System.out.println("=== Scenario 2: Alice books ECONOMY ===");
        Passenger alice = new Passenger("P-1", "Alice Johnson",
            "[email]", "US1234567");
        Booking aliceBooking = bookingService.bookFlight(alice, flight1, FareClass.ECONOMY);
        System.out.println("Booking: " + aliceBooking.getBookingId()
            + " status=" + aliceBooking.getStatus()
            + " fare=$" + aliceBooking.getFare());
        System.out.println("Remaining ECONOMY seats: "
            + flight1.getAvailableSeats(FareClass.ECONOMY));
        System.out.println();

        // ─── Scenario 3: Book BUSINESS ───
        System.out.println("=== Scenario 3: Bob books BUSINESS ===");
        Passenger bob = new Passenger("P-2", "Bob Smith",
            "[email]", "US7654321");
        Booking bobBooking = bookingService.bookFlight(bob, flight1, FareClass.BUSINESS);
        System.out.println("Booking: " + bobBooking.getBookingId()
            + " fare=$" + bobBooking.getFare());
        System.out.println();

        // ─── Scenario 4: Cancellation releases seat ───
        System.out.println("=== Scenario 4: Alice cancels ===");
        int beforeCancel = flight1.getAvailableSeats(FareClass.ECONOMY);
        bookingService.cancelBooking(aliceBooking.getBookingId());
        int afterCancel = flight1.getAvailableSeats(FareClass.ECONOMY);
        System.out.println("ECONOMY seats: " + beforeCancel + " → " + afterCancel
            + " (+" + (afterCancel - beforeCancel) + ")");
        System.out.println("Alice's booking status: " + aliceBooking.getStatus());
        System.out.println();

        // ─── Scenario 5: Concurrent booking — the critical test ───
        System.out.println("=== Scenario 5: 50 threads race for 5 FIRST class seats ===");
        // FIRST has 8 seats total. Book 3 upfront, then 50 threads race for the last 5.
        for (int i = 0; i < 3; i++) {
            Passenger p = new Passenger("P-F" + i, "First" + i,
                "f" + i + "@e.com", "US00" + i);
            bookingService.bookFlight(p, flight1, FareClass.FIRST);
        }
        System.out.println("Pre-race FIRST seats: " + flight1.getAvailableSeats(FareClass.FIRST));

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger soldOut = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Passenger racer = new Passenger("R-" + i, "Racer" + i,
                "r" + i + "@e.com", "R00" + i);
            executor.submit(() -> {
                try {
                    startGate.await();
                    bookingService.bookFlight(racer, flight1, FareClass.FIRST);
                    successes.incrementAndGet();
                } catch (NoSeatsAvailableException e) {
                    soldOut.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();  // release all threads at once
        doneGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("Successes: " + successes.get() + " (expected: 5)");
        System.out.println("Sold out:  " + soldOut.get() + " (expected: 45)");
        System.out.println("Remaining FIRST seats: " + flight1.getAvailableSeats(FareClass.FIRST));

        if (successes.get() == 5 && soldOut.get() == 45
                && flight1.getAvailableSeats(FareClass.FIRST) == 0) {
            System.out.println("✓ CONCURRENCY TEST PASSED — no overbooking, no lost seats");
        } else {
            System.out.println("✗ CONCURRENCY TEST FAILED");
        }
        System.out.println();

        // ─── Scenario 6: Check-in (flight too far out, should fail) ───
        System.out.println("=== Scenario 6: Check-in outside window ===");
        try {
            bookingService.checkIn(bobBooking.getBookingId());
        } catch (AirlineException e) {
            System.out.println("Rejected (expected): " + e.getMessage());
        }
        System.out.println();

        // ─── Scenario 7: Check-in within window (simulated) ───
        System.out.println("=== Scenario 7: Check-in — create near-departure flight ===");
        Instant soonDeparture = Instant.now().plusSeconds(6 * 60 * 60);  // 6h away → in 24h window
        FlightInventory smallInv = new FlightInventory(
            Map.of(FareClass.ECONOMY, 10),
            Map.of(FareClass.ECONOMY, 99.0));
        Flight urgentFlight = new Flight("AA-100", "American", sea, jfk,
            soonDeparture, soonDeparture.plusSeconds(6 * 60 * 60),
            boeing787, smallInv);
        flightService.addFlight(urgentFlight);

        Booking urgent = bookingService.bookFlight(alice, urgentFlight, FareClass.ECONOMY);
        System.out.println("Booked " + urgent.getBookingId() + " for " + urgentFlight);

        Booking checkedIn = bookingService.checkIn(urgent.getBookingId());
        System.out.println("Checked in! Seat: " + checkedIn.getAssignedSeat()
            + " at " + checkedIn.getCheckedInAt());
        System.out.println();

        // ─── Scenario 8: Error cases ───
        System.out.println("=== Scenario 8: Error handling ===");
        // Try to book sold-out FIRST class
        testError(() -> bookingService.bookFlight(alice, flight1, FareClass.FIRST),
            "Book sold-out class");
        // Double-check-in
        testError(() -> bookingService.checkIn(checkedIn.getBookingId()),
            "Check in twice");
        // Cancel non-existent
        testError(() -> bookingService.cancelBooking("PNR-NONEXISTENT"),
            "Cancel non-existent booking");
    }

    private static void testError(Runnable op, String description) {
        try {
            op.run();
            System.out.println("  [FAIL] " + description + " — no exception");
        } catch (AirlineException e) {
            System.out.println("  [OK]   " + description + " → " + e.getMessage());
        }
    }
}
