import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BookMyShow — Movie Ticket Booking System (Interview Style, Single File)
 *
 * Features:
 *   - Browse shows by movie/date
 *   - Lock seats during checkout (prevent double-booking)
 *   - Confirm or release seats
 *   - Booking with payment
 *
 * Concurrency:
 *   - Per-seat ReentrantLock with lock ordering (sorted seat IDs) to prevent deadlock
 *   - Lock → check availability → mark LOCKED → release locks
 *   - On payment success: LOCKED → BOOKED
 *   - On timeout/failure: LOCKED → AVAILABLE
 */
public class BookingController {

    // ═══════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════
    enum SeatStatus { AVAILABLE, LOCKED, BOOKED }
    enum SeatCategory { REGULAR, PREMIUM, VIP }
    enum PaymentStatus { PENDING, SUCCESS, FAILED }

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════
    static class Movie {
        private final String movieId;
        private final String name;

        public Movie(String movieId, String name) {
            this.movieId = movieId;
            this.name = name;
        }

        public String getMovieId() { return movieId; }
        public String getName() { return name; }
    }

    static class Seat {
        private final int seatId;
        private final SeatCategory category;

        public Seat(int seatId, SeatCategory category) {
            this.seatId = seatId;
            this.category = category;
        }

        public int getSeatId() { return seatId; }
        public SeatCategory getCategory() { return category; }
    }

    static class Screen {
        private final int screenId;
        private final List<Seat> seats;
        private final Map<LocalDate, List<Show>> showsByDate;

        public Screen(int screenId, List<Seat> seats) {
            this.screenId = screenId;
            this.seats = seats;
            this.showsByDate = new HashMap<>();
        }

        public int getScreenId() { return screenId; }
        public List<Seat> getSeats() { return seats; }

        public void addShow(Show show) {
            showsByDate.computeIfAbsent(show.getShowDate(), d -> new ArrayList<>()).add(show);
        }

        public List<Show> getShows(LocalDate date) {
            return showsByDate.getOrDefault(date, new ArrayList<>());
        }
    }

    static class Show {
        private final String showId;
        private final Movie movie;
        private final LocalDate showDate;
        private final LocalTime startTime;
        private final Map<Integer, SeatStatus> seatStatusMap;
        private final Map<Integer, ReentrantLock> seatLocks;

        public Show(String showId, Movie movie, Screen screen, LocalDate date, LocalTime time) {
            this.showId = showId;
            this.movie = movie;
            this.showDate = date;
            this.startTime = time;
            this.seatStatusMap = new HashMap<>();
            this.seatLocks = new HashMap<>();

            for (Seat seat : screen.getSeats()) {
                seatStatusMap.put(seat.getSeatId(), SeatStatus.AVAILABLE);
                seatLocks.put(seat.getSeatId(), new ReentrantLock());
            }
        }

        public String getShowId() { return showId; }
        public Movie getMovie() { return movie; }
        public LocalDate getShowDate() { return showDate; }
        public LocalTime getStartTime() { return startTime; }

        public boolean isSeatAvailable(int seatId) {
            return seatStatusMap.get(seatId) == SeatStatus.AVAILABLE;
        }

        public List<Integer> getAvailableSeats() {
            List<Integer> available = new ArrayList<>();
            for (Map.Entry<Integer, SeatStatus> entry : seatStatusMap.entrySet()) {
                if (entry.getValue() == SeatStatus.AVAILABLE) {
                    available.add(entry.getKey());
                }
            }
            return available;
        }

        /**
         * Lock seats for checkout — prevents double-booking.
         *
         * Approach:
         *   1. Sort seat IDs (consistent lock ordering → no deadlock)
         *   2. Acquire per-seat locks
         *   3. Verify all seats still AVAILABLE
         *   4. Mark as LOCKED
         *   5. Release all locks
         *
         * If any seat is not available, returns false (no partial locking).
         */
        public boolean lockSeats(List<Integer> seatIds) {
            List<Integer> sorted = new ArrayList<>(seatIds);
            Collections.sort(sorted);
            List<ReentrantLock> acquiredLocks = new ArrayList<>();

            try {
                // Phase 1: Acquire locks in order
                for (int seatId : sorted) {
                    ReentrantLock lock = seatLocks.get(seatId);
                    if (lock == null) return false;
                    lock.lock();
                    acquiredLocks.add(lock);
                }

                // Phase 2: Check all seats available
                for (int seatId : sorted) {
                    if (seatStatusMap.get(seatId) != SeatStatus.AVAILABLE) {
                        return false; // Someone else got here first
                    }
                }

                // Phase 3: Mark all as LOCKED
                for (int seatId : sorted) {
                    seatStatusMap.put(seatId, SeatStatus.LOCKED);
                }
                return true;

            } finally {
                // Phase 4: Always release locks
                for (ReentrantLock lock : acquiredLocks) {
                    lock.unlock();
                }
            }
        }

        /**
         * Confirm seats after successful payment: LOCKED → BOOKED
         */
        public void confirmSeats(List<Integer> seatIds) {
            for (int seatId : seatIds) {
                seatStatusMap.put(seatId, SeatStatus.BOOKED);
            }
        }

        /**
         * Release seats on payment failure or timeout: LOCKED → AVAILABLE
         */
        public void releaseSeats(List<Integer> seatIds) {
            for (int seatId : seatIds) {
                seatStatusMap.put(seatId, SeatStatus.AVAILABLE);
            }
        }
    }

    static class Payment {
        private final String paymentId;
        private PaymentStatus status;

        public Payment(PaymentStatus status) {
            this.paymentId = UUID.randomUUID().toString();
            this.status = status;
        }

        public String getPaymentId() { return paymentId; }
        public PaymentStatus getStatus() { return status; }
        public void setStatus(PaymentStatus status) { this.status = status; }
    }

    static class User {
        private final String userId;
        private final String name;

        public User(String userId, String name) {
            this.userId = userId;
            this.name = name;
        }

        public String getUserId() { return userId; }
        public String getName() { return name; }
    }

    static class Booking {
        private final String bookingId;
        private final User user;
        private final Show show;
        private final List<Integer> seatIds;
        private final Payment payment;

        public Booking(User user, Show show, List<Integer> seatIds, Payment payment) {
            this.bookingId = UUID.randomUUID().toString();
            this.user = user;
            this.show = show;
            this.seatIds = seatIds;
            this.payment = payment;
        }

        public String getBookingId() { return bookingId; }
        public User getUser() { return user; }
        public Show getShow() { return show; }
        public List<Integer> getSeatIds() { return seatIds; }
        public Payment getPayment() { return payment; }
    }

    // ═══════════════════════════════════════════════
    // Service Layer
    // ═══════════════════════════════════════════════
    static class BookingService {
        private final Map<String, Booking> bookings;
        private final Map<String, List<Booking>> userBookings;

        public BookingService() {
            this.bookings = new HashMap<>();
            this.userBookings = new HashMap<>();
        }

        /**
         * Full booking flow:
         *   1. Lock seats (prevents double-booking)
         *   2. Process payment
         *   3. On success: confirm seats, create booking
         *   4. On failure: release seats
         */
        public Booking createBooking(User user, Show show, List<Integer> seatIds) {
            // Step 1: Lock seats
            boolean locked = show.lockSeats(seatIds);
            if (!locked) {
                throw new RuntimeException("Seats unavailable — someone else booked them");
            }

            // Step 2: Process payment (simulated)
            Payment payment = processPayment(user, seatIds.size());

            if (payment.getStatus() == PaymentStatus.SUCCESS) {
                // Step 3a: Confirm
                show.confirmSeats(seatIds);
                Booking booking = new Booking(user, show, seatIds, payment);
                bookings.put(booking.getBookingId(), booking);
                userBookings.computeIfAbsent(user.getUserId(), k -> new ArrayList<>()).add(booking);
                return booking;
            } else {
                // Step 3b: Release on failure
                show.releaseSeats(seatIds);
                throw new RuntimeException("Payment failed — seats released");
            }
        }

        public Booking getBooking(String bookingId) {
            return bookings.get(bookingId);
        }

        public List<Booking> getBookingsForUser(String userId) {
            return userBookings.getOrDefault(userId, new ArrayList<>());
        }

        private Payment processPayment(User user, int seatCount) {
            // Simulated payment — always succeeds for demo
            return new Payment(PaymentStatus.SUCCESS);
        }
    }

    static class TheatreService {
        private final Map<String, Screen> screens;
        private final Map<String, Movie> movies;

        public TheatreService() {
            this.screens = new HashMap<>();
            this.movies = new HashMap<>();
        }

        public void addScreen(Screen screen) {
            screens.put(String.valueOf(screen.getScreenId()), screen);
        }

        public void addMovie(Movie movie) {
            movies.put(movie.getMovieId(), movie);
        }

        public List<Show> getShowsForDate(int screenId, LocalDate date) {
            Screen screen = screens.get(String.valueOf(screenId));
            if (screen == null) return new ArrayList<>();
            return screen.getShows(date);
        }
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════
    public static void main(String[] args) {
        // Setup
        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            SeatCategory cat = i <= 4 ? SeatCategory.REGULAR : (i <= 8 ? SeatCategory.PREMIUM : SeatCategory.VIP);
            seats.add(new Seat(i, cat));
        }

        Screen screen = new Screen(1, seats);
        Movie movie = new Movie("M1", "Inception");
        Show show = new Show("S1", movie, screen, LocalDate.of(2025, 1, 15), LocalTime.of(18, 30));
        screen.addShow(show);

        BookingService bookingService = new BookingService();
        User userA = new User("U1", "Alice");
        User userB = new User("U2", "Bob");

        System.out.println("═══ BookMyShow — Booking System ═══\n");
        System.out.println("Available seats: " + show.getAvailableSeats());

        // User A books seats 1, 2, 3
        System.out.println("\n--- Alice books seats [1, 2, 3] ---");
        Booking bookingA = bookingService.createBooking(userA, show, Arrays.asList(1, 2, 3));
        System.out.println("Booking created: " + bookingA.getBookingId());
        System.out.println("Available seats after: " + show.getAvailableSeats());

        // User B tries to book seat 2 (already booked)
        System.out.println("\n--- Bob tries seats [2, 4] ---");
        try {
            bookingService.createBooking(userB, show, Arrays.asList(2, 4));
        } catch (RuntimeException e) {
            System.out.println("Failed: " + e.getMessage());
        }

        // User B books seats 5, 6
        System.out.println("\n--- Bob books seats [5, 6] ---");
        Booking bookingB = bookingService.createBooking(userB, show, Arrays.asList(5, 6));
        System.out.println("Booking created: " + bookingB.getBookingId());
        System.out.println("Available seats after: " + show.getAvailableSeats());

        // Query bookings
        System.out.println("\nAlice's bookings: " + bookingService.getBookingsForUser("U1").size());
        System.out.println("Bob's bookings: " + bookingService.getBookingsForUser("U2").size());
    }
}
