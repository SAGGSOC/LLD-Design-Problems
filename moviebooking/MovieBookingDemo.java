package moviebooking;

import moviebooking.enums.BookingStatus;
import moviebooking.enums.SeatType;
import moviebooking.exception.MovieBookingException;
import moviebooking.exception.SeatUnavailableException;
import moviebooking.model.*;
import moviebooking.service.BookingService;
import moviebooking.service.PaymentGateway;
import moviebooking.service.SearchService;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MovieBookingDemo {

    public static void main(String[] args) throws Exception {
        // ─── Setup ───
        SearchService searchService = new SearchService();
        PaymentGateway gateway = new PaymentGateway(1.0);  // 100% success for predictable demo
        BookingService bookingService = new BookingService(gateway);

        // Create movie, cinema, screen, show
        Movie inception = new Movie("MOV-001", "Inception", "English",
            Arrays.asList("Sci-Fi", "Thriller"),
            Duration.ofMinutes(148), "PG-13", 8.8);
        searchService.addMovie(inception);

        Screen screen1 = buildScreen("SCR-1", "Screen 1", "CIN-001");
        Cinema cinema = new Cinema("CIN-001", "AMC Downtown", "Seattle",
            "1 Main St", Collections.singletonList(screen1));
        searchService.addCinema(cinema);

        Show show = new Show("SHOW-001", inception, screen1,
            Instant.parse("2026-05-15T19:00:00Z"),
            Instant.parse("2026-05-15T21:30:00Z"),
            1.5);  // prime time — 1.5x base
        searchService.addShow(show);

        // Create users
        User alice   = new User("USR-1", "Alice",   "[email]",   "555-0001");
        User bob     = new User("USR-2", "Bob",     "[email]",     "555-0002");
        User charlie = new User("USR-3", "Charlie", "[email]", "555-0003");

        // ─── Scenario 1: Happy path booking ───
        System.out.println("=== Scenario 1: Alice books A-5 and A-6 ===");
        Booking aliceBooking = bookingService.holdSeats(
            alice, show, Arrays.asList("SCR-1-A-5", "SCR-1-A-6"));
        System.out.println("Hold created: " + aliceBooking.getBookingId()
            + " (status=" + aliceBooking.getStatus() + ")");
        System.out.printf("Total: $%.2f%n", aliceBooking.getTotalAmount());

        Booking confirmed = bookingService.confirmPayment(aliceBooking.getBookingId());
        System.out.println("Confirmed: " + confirmed.getStatus()
            + " with payment " + confirmed.getPaymentId());
        System.out.println();

        // ─── Scenario 2: Someone else tries to book Alice's seats ───
        System.out.println("=== Scenario 2: Bob tries Alice's seats (should fail) ===");
        try {
            bookingService.holdSeats(bob, show,
                Arrays.asList("SCR-1-A-5", "SCR-1-A-7"));
        } catch (SeatUnavailableException e) {
            System.out.println("Rejected: " + e.getMessage());
            // Verify A-7 was NOT held (all-or-nothing rollback)
            System.out.println("A-7 status after failed attempt: "
                + show.getSeatStatus("SCR-1-A-7"));
        }
        System.out.println();

        // ─── Scenario 3: CONCURRENT booking — the critical test ───
        System.out.println("=== Scenario 3: 100 users race for seat A-20 ===");
        runConcurrencyTest(show, bookingService);
        System.out.println();

        // ─── Scenario 4: Hold timeout ───
        System.out.println("=== Scenario 4: Hold expiry (simulated) ===");
        // Normally 5 min. For demo, we'll override by hand.
        demonstrateHoldExpiry(show, bob);
        System.out.println();

        // ─── Scenario 5: Available seat count ───
        System.out.println("=== Scenario 5: Availability after all bookings ===");
        System.out.println("Available seats: " + show.getAvailableSeats().size()
            + " / " + screen1.getTotalSeats());
    }

    /**
     * 100 concurrent threads try to book the SAME seat (A-20).
     * Exactly ONE should succeed. 99 should fail with SeatUnavailable.
     */
    private static void runConcurrencyTest(Show show, BookingService bookingService)
            throws Exception {
        int threadCount = 100;
        String targetSeat = "SCR-1-A-20";
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startGate = new CountDownLatch(1);   // all threads start together
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            User racer = new User("RACER-" + i, "Racer" + i, "r" + i + "@e.com", "555");
            executor.submit(() -> {
                try {
                    startGate.await();  // wait for the "go" signal
                    Booking booking = bookingService.holdSeats(
                        racer, show, Collections.singletonList(targetSeat));
                    // Only one racer should reach here
                    bookingService.confirmPayment(booking.getBookingId());
                    successes.incrementAndGet();
                } catch (MovieBookingException e) {
                    failures.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startGate.countDown();
        doneGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("Result:");
        System.out.println("  Successes: " + successes.get() + " (expected: 1)");
        System.out.println("  Failures:  " + failures.get() + " (expected: 99)");
        System.out.println("  Seat A-20 final status: " + show.getSeatStatus(targetSeat));

        if (successes.get() == 1 && failures.get() == 99) {
            System.out.println("  ✓ CONCURRENCY TEST PASSED");
        } else {
            System.out.println("  ✗ CONCURRENCY TEST FAILED — race condition!");
        }
    }

    private static void demonstrateHoldExpiry(Show show, User bob) throws Exception {
        // Hold with a 1-second timeout for the demo
        boolean held = show.tryHoldSeat("SCR-1-A-30", bob.getUserId(), 1);
        System.out.println("Bob holds A-30: " + held);
        System.out.println("  Status immediately: " + show.getSeatStatus("SCR-1-A-30"));

        Thread.sleep(1500);  // wait past expiry

        // Next status check triggers lazy expiry
        System.out.println("  Status after 1.5s (past 1s expiry): "
            + show.getSeatStatus("SCR-1-A-30"));
    }

    private static Screen buildScreen(String screenId, String name, String cinemaId) {
        List<Seat> seats = new ArrayList<>();
        // 5 rows (A-E), 30 seats per row
        String[] rows = {"A", "B", "C", "D", "E"};
        for (String row : rows) {
            for (int num = 1; num <= 30; num++) {
                SeatType type;
                double price;
                if (row.equals("A") || row.equals("B")) {
                    type = SeatType.RECLINER;
                    price = 25.0;
                } else if (row.equals("E")) {
                    type = SeatType.PREMIUM;
                    price = 18.0;
                } else {
                    type = SeatType.REGULAR;
                    price = 12.0;
                }
                String seatId = screenId + "-" + row + "-" + num;
                seats.add(new Seat(seatId, row, num, type, price));
            }
        }
        return new Screen(screenId, name, cinemaId, seats);
    }
}
