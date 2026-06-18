import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Movie Booking System — Thread-Safe, Interview Ready
 *
 * Features:
 *   - Search movies by title (returns future showtimes)
 *   - Get showtimes at a theater
 *   - Book seats (with concurrency protection)
 *   - Cancel reservations (frees seats)
 *
 * Concurrency:
 *   - ReentrantLock per Showtime (booking same seats race condition)
 *   - ConcurrentHashMap for reservations (concurrent book + cancel)
 *
 * Models included: Theater, Movie, Showtime, Seat, Reservation
 */
public class BookingSystem {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    enum SeatStatus { AVAILABLE, BOOKED }

    static class Movie {
        private final String id;
        private final String title;
        private final int durationMinutes;

        Movie(String id, String title, int durationMinutes) {
            this.id = id;
            this.title = title;
            this.durationMinutes = durationMinutes;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public int getDurationMinutes() { return durationMinutes; }
    }

    static class Seat {
        private final String seatId;
        private volatile SeatStatus status;

        Seat(String seatId) {
            this.seatId = seatId;
            this.status = SeatStatus.AVAILABLE;
        }

        public String getSeatId() { return seatId; }
        public SeatStatus getStatus() { return status; }
        public boolean isAvailable() { return status == SeatStatus.AVAILABLE; }
        public void book() { status = SeatStatus.BOOKED; }
        public void release() { status = SeatStatus.AVAILABLE; }
    }

    static class Showtime {
        private final String id;
        private final Movie movie;
        private final LocalDateTime datetime;
        private final String theaterId;
        private final Map<String, Seat> seats; // seatId → Seat
        private final Map<String, ReentrantLock> seatLocks; // per-seat lock

        Showtime(String id, Movie movie, LocalDateTime datetime, String theaterId, List<String> seatIds) {
            this.id = id;
            this.movie = movie;
            this.datetime = datetime;
            this.theaterId = theaterId;
            this.seats = new LinkedHashMap<>();
            this.seatLocks = new HashMap<>();
            for (String seatId : seatIds) {
                seats.put(seatId, new Seat(seatId));
                seatLocks.put(seatId, new ReentrantLock());
            }
        }

        public String getId() { return id; }
        public Movie getMovie() { return movie; }
        public LocalDateTime getDatetime() { return datetime; }
        public String getTheaterId() { return theaterId; }

        public List<String> getAvailableSeats() {
            List<String> available = new ArrayList<>();
            for (Seat seat : seats.values()) {
                if (seat.isAvailable()) available.add(seat.getSeatId());
            }
            return available;
        }

        /**
         * Book seats with per-seat locking + sorted lock ordering.
         *
         * Why per-seat:
         *   Booking A1-A2 doesn't block booking Z1-Z2 (independent seats).
         *   Only truly conflicting seats serialize.
         *
         * Why sorted:
         *   Thread A: books [A2, A1] → locks A1 then A2
         *   Thread B: books [A1, A2] → locks A1 then A2 (same order!)
         *   Without sorting: A locks A2, B locks A1 → deadlock.
         *
         * All-or-nothing: if any seat is taken, no seats are booked.
         */
        public void book(Reservation reservation) {
            List<String> sorted = new ArrayList<>(reservation.getSeatIds());
            Collections.sort(sorted); // consistent lock ordering

            List<ReentrantLock> acquired = new ArrayList<>();
            try {
                // Phase 1: Acquire per-seat locks in sorted order
                for (String seatId : sorted) {
                    ReentrantLock lock = seatLocks.get(seatId);
                    if (lock == null) throw new IllegalArgumentException("Seat not found: " + seatId);
                    lock.lock();
                    acquired.add(lock);
                }

                // Phase 2: Verify all seats available
                for (String seatId : sorted) {
                    Seat seat = seats.get(seatId);
                    if (!seat.isAvailable()) {
                        throw new IllegalStateException("Seat already booked: " + seatId);
                    }
                }

                // Phase 3: Book all seats
                for (String seatId : sorted) {
                    seats.get(seatId).book();
                }

            } finally {
                // Phase 4: Release all locks (always, even on exception)
                for (ReentrantLock lock : acquired) {
                    lock.unlock();
                }
            }
        }

        /**
         * Cancel reservation — release seats with per-seat locks.
         */
        public void cancel(Reservation reservation) {
            List<String> sorted = new ArrayList<>(reservation.getSeatIds());
            Collections.sort(sorted);

            List<ReentrantLock> acquired = new ArrayList<>();
            try {
                for (String seatId : sorted) {
                    ReentrantLock lock = seatLocks.get(seatId);
                    if (lock != null) {
                        lock.lock();
                        acquired.add(lock);
                    }
                }

                for (String seatId : sorted) {
                    Seat seat = seats.get(seatId);
                    if (seat != null) seat.release();
                }

            } finally {
                for (ReentrantLock lock : acquired) {
                    lock.unlock();
                }
            }
        }

        @Override
        public String toString() {
            return movie.getTitle() + " @ " + theaterId + " | " + datetime + " | Available: " + getAvailableSeats().size();
        }
    }

    static class Theater {
        private final String id;
        private final String name;
        private final List<Showtime> showtimes;

        Theater(String id, String name) {
            this.id = id;
            this.name = name;
            this.showtimes = new ArrayList<>();
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public List<Showtime> getShowtimes() { return showtimes; }
        public void addShowtime(Showtime showtime) { showtimes.add(showtime); }
    }

    static class Reservation {
        private final String confirmationId;
        private final Showtime showtime;
        private final List<String> seatIds;

        Reservation(String confirmationId, Showtime showtime, List<String> seatIds) {
            this.confirmationId = confirmationId;
            this.showtime = showtime;
            this.seatIds = seatIds;
        }

        public String getConfirmationId() { return confirmationId; }
        public Showtime getShowtime() { return showtime; }
        public List<String> getSeatIds() { return seatIds; }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final List<Theater> theaters;
    private final Map<String, Movie> moviesById;
    private final Map<String, List<Showtime>> showtimesByMovieId;
    private final Map<String, Showtime> showtimesById;
    private final Map<String, Reservation> reservationsById = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════

    public BookingSystem(List<Theater> theaters) {
        this.theaters = theaters;
        this.moviesById = new HashMap<>();
        this.showtimesByMovieId = new HashMap<>();
        this.showtimesById = new HashMap<>();

        for (Theater theater : theaters) {
            for (Showtime showtime : theater.getShowtimes()) {
                Movie movie = showtime.getMovie();
                moviesById.put(movie.getId(), movie);
                showtimesById.put(showtime.getId(), showtime);
                showtimesByMovieId.computeIfAbsent(movie.getId(), k -> new ArrayList<>()).add(showtime);
            }
        }
    }

    // ═══════════════════════════════════════════════
    // Search Movies
    // ═══════════════════════════════════════════════

    public List<Showtime> searchMovies(String title) {
        if (title == null || title.isEmpty()) return new ArrayList<>();

        List<Showtime> results = new ArrayList<>();
        String searchLower = title.toLowerCase();
        LocalDateTime now = LocalDateTime.now();

        for (Movie movie : moviesById.values()) {
            if (movie.getTitle().toLowerCase().contains(searchLower)) {
                List<Showtime> movieShowtimes = showtimesByMovieId.get(movie.getId());
                if (movieShowtimes != null) {
                    for (Showtime showtime : movieShowtimes) {
                        if (showtime.getDatetime().isAfter(now)) {
                            results.add(showtime);
                        }
                    }
                }
            }
        }
        return results;
    }

    // ═══════════════════════════════════════════════
    // Get Showtimes at Theater
    // ═══════════════════════════════════════════════

    public List<Showtime> getShowtimesAtTheater(Theater theater) {
        if (theater == null) return new ArrayList<>();

        List<Showtime> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Showtime showtime : theater.getShowtimes()) {
            if (showtime.getDatetime().isAfter(now)) {
                results.add(showtime);
            }
        }
        return results;
    }

    // ═══════════════════════════════════════════════
    // Book Seats
    // ═══════════════════════════════════════════════

    public Reservation book(String showtimeId, List<String> seatIds) {
        if (showtimeId == null || seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("Invalid booking request");
        }

        Showtime showtime = showtimesById.get(showtimeId);
        if (showtime == null) {
            throw new NoSuchElementException("Showtime not found: " + showtimeId);
        }

        // Reservation created, then booked atomically (lock inside showtime.book)
        Reservation reservation = new Reservation(
            UUID.randomUUID().toString(), showtime, seatIds);

        showtime.book(reservation); // throws if seats unavailable (all-or-nothing)
        reservationsById.put(reservation.getConfirmationId(), reservation);
        return reservation;
    }

    // ═══════════════════════════════════════════════
    // Cancel Reservation
    // ═══════════════════════════════════════════════

    public void cancelReservation(String confirmationId) {
        if (confirmationId == null || confirmationId.isEmpty()) {
            throw new IllegalArgumentException("Invalid confirmation ID");
        }

        Reservation reservation = reservationsById.get(confirmationId);
        if (reservation == null) {
            throw new NoSuchElementException("Reservation not found: " + confirmationId);
        }

        reservation.getShowtime().cancel(reservation); // lock inside showtime.cancel
        reservationsById.remove(confirmationId);
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        // Setup
        Movie movie1 = new Movie("M1", "Inception", 148);
        Movie movie2 = new Movie("M2", "The Dark Knight", 152);

        List<String> seatIds = Arrays.asList("A1", "A2", "A3", "A4", "A5", "B1", "B2", "B3", "B4", "B5");

        Showtime st1 = new Showtime("ST1", movie1, LocalDateTime.now().plusHours(2), "T1", seatIds);
        Showtime st2 = new Showtime("ST2", movie1, LocalDateTime.now().plusHours(5), "T1", seatIds);
        Showtime st3 = new Showtime("ST3", movie2, LocalDateTime.now().plusHours(3), "T1", seatIds);

        Theater theater = new Theater("T1", "PVR Cinemas");
        theater.addShowtime(st1);
        theater.addShowtime(st2);
        theater.addShowtime(st3);

        BookingSystem system = new BookingSystem(Arrays.asList(theater));

        System.out.println("═══ Movie Booking System ═══\n");

        // Search
        System.out.println("--- Search 'inception' ---");
        List<Showtime> results = system.searchMovies("inception");
        results.forEach(s -> System.out.println("  " + s));

        // Book
        System.out.println("\n--- Book seats A1, A2 for ST1 ---");
        Reservation res1 = system.book("ST1", Arrays.asList("A1", "A2"));
        System.out.println("Booked: " + res1.getConfirmationId() + " seats=" + res1.getSeatIds());
        System.out.println("Available after booking: " + st1.getAvailableSeats());

        // Try to book same seat (should fail)
        System.out.println("\n--- Try booking A1 again ---");
        try {
            system.book("ST1", Arrays.asList("A1", "A3"));
        } catch (IllegalStateException e) {
            System.out.println("Failed: " + e.getMessage());
        }

        // Book more seats
        Reservation res2 = system.book("ST1", Arrays.asList("A3", "A4"));
        System.out.println("Booked: " + res2.getConfirmationId() + " seats=" + res2.getSeatIds());

        // Cancel first reservation
        System.out.println("\n--- Cancel first booking ---");
        system.cancelReservation(res1.getConfirmationId());
        System.out.println("Available after cancel: " + st1.getAvailableSeats());

        // Now A1, A2 are available again
        System.out.println("\n--- Book A1 after cancel ---");
        Reservation res3 = system.book("ST1", Arrays.asList("A1"));
        System.out.println("Booked: " + res3.getConfirmationId() + " seats=" + res3.getSeatIds());

        // Concurrent booking test
        System.out.println("\n--- Concurrent booking (5 threads for B1) ---");
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    Reservation r = system.book("ST1", Arrays.asList("B1"));
                    System.out.println("  Thread " + idx + ": SUCCESS " + r.getConfirmationId());
                } catch (Exception e) {
                    System.out.println("  Thread " + idx + ": FAILED - " + e.getMessage());
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) {}
        }
        System.out.println("B1 status: " + (st1.getAvailableSeats().contains("B1") ? "AVAILABLE" : "BOOKED"));
    }
}
