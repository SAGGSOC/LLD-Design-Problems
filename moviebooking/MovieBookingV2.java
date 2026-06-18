import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Movie Booking System V2 — Uses original design with:
 *   - Implicit seat grid (A0-Z20, no pre-creation needed)
 *   - Reservation list per Showtime (availability derived by scanning reservations)
 *   - synchronized(this) on Showtime for thread-safe booking/cancel
 *   - BookingSystem as facade
 */
public class MovieBookingV2 {

    // ═══════════════════════════════════════════════
    // Movie
    // ═══════════════════════════════════════════════

    static class Movie {
        private final String id;
        private final String title;

        public Movie(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
    }

    // ═══════════════════════════════════════════════
    // Reservation
    // ═══════════════════════════════════════════════

    static class Reservation {
        private final String confirmationId;
        private final Showtime showtime;
        private final List<String> seatIds;

        public Reservation(String confirmationId, Showtime showtime, List<String> seatIds) {
            this.confirmationId = confirmationId;
            this.showtime = showtime;
            this.seatIds = seatIds;
        }

        public String getConfirmationId() { return confirmationId; }
        public Showtime getShowtime() { return showtime; }
        public List<String> getSeatIds() { return seatIds; }
    }

    // ═══════════════════════════════════════════════
    // Showtime
    // ═══════════════════════════════════════════════

    static class Showtime {
        private final String id;
        private final String theaterId;
        private final Movie movie;
        private final LocalDateTime datetime;
        private final String screenLabel;
        // Per-seat status: seatId → booked or not
        private final Map<String, Boolean> seatStatus; // true = booked
        private final Map<String, ReentrantLock> seatLocks;

        public Showtime(String id, String theaterId, Movie movie, LocalDateTime datetime, String screenLabel) {
            this.id = id;
            this.theaterId = theaterId;
            this.movie = movie;
            this.datetime = datetime;
            this.screenLabel = screenLabel;
            this.seatStatus = new ConcurrentHashMap<>();
            this.seatLocks = new ConcurrentHashMap<>();

            // Initialize all seats A0-Z20 as available
            for (char row = 'A'; row <= 'Z'; row++) {
                for (int num = 0; num <= 20; num++) {
                    String seatId = "" + row + num;
                    seatStatus.put(seatId, false); // false = available
                    seatLocks.put(seatId, new ReentrantLock());
                }
            }
        }

        public String getId() { return id; }
        public String getTheaterId() { return theaterId; }
        public Movie getMovie() { return movie; }
        public LocalDateTime getDatetime() { return datetime; }

        public boolean isAvailable(String seatId) {
            Boolean booked = seatStatus.get(seatId);
            return booked != null && !booked;
        }

        public List<String> getAvailableSeats() {
            List<String> available = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : seatStatus.entrySet()) {
                if (!entry.getValue()) {
                    available.add(entry.getKey());
                }
            }
            Collections.sort(available);
            return available;
        }

        /**
         * Book seats with per-seat locking + sorted lock ordering.
         * No shared mutable list — each seat has independent boolean status.
         */
        public void book(List<String> seatIds) {
            if (seatIds == null || seatIds.isEmpty()) {
                throw new IllegalArgumentException("Must select at least one seat");
            }

            List<String> sorted = new ArrayList<>(seatIds);
            Collections.sort(sorted);

            for (String seatId : sorted) {
                if (!isValidSeatId(seatId)) {
                    throw new IllegalArgumentException("Invalid seat: " + seatId);
                }
            }

            List<ReentrantLock> acquired = new ArrayList<>();
            try {
                // Acquire per-seat locks in sorted order
                for (String seatId : sorted) {
                    ReentrantLock lock = seatLocks.get(seatId);
                    lock.lock();
                    acquired.add(lock);
                }

                // Verify all available
                for (String seatId : sorted) {
                    if (seatStatus.get(seatId)) {
                        throw new IllegalStateException("Seat " + seatId + " is not available");
                    }
                }

                // Mark all as booked
                for (String seatId : sorted) {
                    seatStatus.put(seatId, true);
                }

            } finally {
                for (ReentrantLock lock : acquired) {
                    lock.unlock();
                }
            }
        }

        /**
         * Release seats on cancellation.
         */
        public void release(List<String> seatIds) {
            List<String> sorted = new ArrayList<>(seatIds);
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
                    seatStatus.put(seatId, false);
                }
            } finally {
                for (ReentrantLock lock : acquired) {
                    lock.unlock();
                }
            }
        }

        private boolean isValidSeatId(String seatId) {
            if (seatId == null || seatId.length() < 2) return false;
            char row = seatId.charAt(0);
            try {
                int num = Integer.parseInt(seatId.substring(1));
                return row >= 'A' && row <= 'Z' && num >= 0 && num <= 20;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            long bookedCount = seatStatus.values().stream().filter(b -> b).count();
            return movie.getTitle() + " | " + datetime + " | Screen: " + screenLabel + " | Booked: " + bookedCount;
        }
    }

    // ═══════════════════════════════════════════════
    // Theater
    // ═══════════════════════════════════════════════

    static class Theater {
        private final String id;
        private final String name;
        private final List<Showtime> showtimes;

        public Theater(String id, String name) {
            this.id = id;
            this.name = name;
            this.showtimes = new ArrayList<>();
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public List<Showtime> getShowtimes() { return showtimes; }

        public void addShowtime(Showtime showtime) {
            showtimes.add(showtime);
        }

        public List<Showtime> getShowtimesForMovie(Movie movie) {
            List<Showtime> results = new ArrayList<>();
            for (Showtime showtime : showtimes) {
                if (showtime.getMovie().getId().equals(movie.getId())) {
                    results.add(showtime);
                }
            }
            return results;
        }
    }

    // ═══════════════════════════════════════════════
    // Booking System (Facade)
    // ═══════════════════════════════════════════════

    static class BookingSystem {
        private final List<Theater> theaters;
        private final Map<String, Movie> moviesById;
        private final Map<String, List<Showtime>> showtimesByMovieId;
        private final Map<String, Showtime> showtimesById;
        private final Map<String, Reservation> reservationsById;

        public BookingSystem(List<Theater> theaters) {
            this.theaters = theaters;
            this.moviesById = new HashMap<>();
            this.showtimesByMovieId = new HashMap<>();
            this.showtimesById = new HashMap<>();
            this.reservationsById = new HashMap<>();

            for (Theater theater : theaters) {
                for (Showtime showtime : theater.getShowtimes()) {
                    Movie movie = showtime.getMovie();
                    moviesById.put(movie.getId(), movie);
                    showtimesById.put(showtime.getId(), showtime);
                    showtimesByMovieId.computeIfAbsent(movie.getId(), k -> new ArrayList<>()).add(showtime);
                }
            }
        }

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

        public Reservation book(String showtimeId, List<String> seatIds) {
            if (showtimeId == null || seatIds == null || seatIds.isEmpty()) {
                throw new IllegalArgumentException("Invalid booking request");
            }

            Showtime showtime = showtimesById.get(showtimeId);
            if (showtime == null) {
                throw new NoSuchElementException("Showtime not found: " + showtimeId);
            }

            // Book seats (per-seat locking inside)
            showtime.book(seatIds);

            Reservation reservation = new Reservation(
                UUID.randomUUID().toString(), showtime, seatIds);
            reservationsById.put(reservation.getConfirmationId(), reservation);
            return reservation;
        }

        public void cancelReservation(String confirmationId) {
            if (confirmationId == null || confirmationId.isEmpty()) {
                throw new IllegalArgumentException("Invalid confirmation ID");
            }

            Reservation reservation = reservationsById.get(confirmationId);
            if (reservation == null) {
                throw new NoSuchElementException("Reservation not found: " + confirmationId);
            }

            reservation.getShowtime().release(reservation.getSeatIds());
            reservationsById.remove(confirmationId);
        }
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        Movie inception = new Movie("M1", "Inception");
        Movie darkKnight = new Movie("M2", "The Dark Knight");

        Theater pvr = new Theater("T1", "PVR Cinemas");

        Showtime st1 = new Showtime("ST1", pvr.getId(), inception, LocalDateTime.now().plusHours(2), "Screen 1");
        Showtime st2 = new Showtime("ST2", pvr.getId(), darkKnight, LocalDateTime.now().plusHours(4), "Screen 2");
        pvr.addShowtime(st1);
        pvr.addShowtime(st2);

        BookingSystem system = new BookingSystem(Arrays.asList(pvr));

        System.out.println("═══ Movie Booking V2 (Original Design) ═══\n");

        // Search
        System.out.println("--- Search 'inception' ---");
        system.searchMovies("inception").forEach(s -> System.out.println("  " + s));

        // Book
        System.out.println("\n--- Book A1, A2, A3 for Inception ---");
        Reservation r1 = system.book("ST1", Arrays.asList("A1", "A2", "A3"));
        System.out.println("Booked: " + r1.getConfirmationId());
        System.out.println("Available seats count: " + st1.getAvailableSeats().size()); // 546 - 3 = 543

        // Double book attempt
        System.out.println("\n--- Try A1 again ---");
        try {
            system.book("ST1", Arrays.asList("A1"));
        } catch (IllegalStateException e) {
            System.out.println("Failed: " + e.getMessage());
        }

        // Invalid seat
        System.out.println("\n--- Try invalid seat ---");
        try {
            system.book("ST1", Arrays.asList("Z99"));
        } catch (IllegalArgumentException e) {
            System.out.println("Failed: " + e.getMessage());
        }

        // Cancel
        System.out.println("\n--- Cancel booking ---");
        system.cancelReservation(r1.getConfirmationId());
        System.out.println("A1 available after cancel: " + st1.isAvailable("A1")); // true

        // Concurrent test
        System.out.println("\n--- Concurrent: 5 threads for B5 ---");
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    system.book("ST1", Arrays.asList("B5"));
                    System.out.println("  Thread " + idx + ": SUCCESS");
                } catch (Exception e) {
                    System.out.println("  Thread " + idx + ": FAILED - " + e.getMessage());
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) { try { t.join(); } catch (InterruptedException e) {} }
        System.out.println("B5 available: " + st1.isAvailable("B5")); // false
    }
}
