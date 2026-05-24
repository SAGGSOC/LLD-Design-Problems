import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BookMyShow — interview-ready, single-file implementation (~180 lines).
 *
 * Focus: the 30-min scope is seat-selection concurrency + two-phase booking.
 * Everything else (payment, search, notifications) is either stubbed or omitted.
 *
 * Design highlights to call out in the interview:
 *   - Per-seat ReentrantLock for atomic hold → different seats never block each other
 *   - Hold with timeout, then confirm on payment → prevents abandoned carts from blocking
 *   - Sorted seat IDs before locking → prevents deadlock between threads holding overlapping sets
 */
public class BookMyShow {

    // ─── Enums ───
    enum SeatStatus    { AVAILABLE, HELD, BOOKED }
    enum BookingStatus { PENDING, CONFIRMED, CANCELLED, EXPIRED }

    // ─── Models ───

    static class Movie {
        final String id, title;
        Movie(String id, String title) { this.id = id; this.title = title; }
    }

    static class Seat {
        final String seatId;   // e.g. "A-1"
        final double price;
        Seat(String seatId, double price) { this.seatId = seatId; this.price = price; }
    }

    static class Show {
        final String showId;
        final Movie movie;
        final Instant startTime;
        final Map<String, Seat> seatById = new LinkedHashMap<>();
        final Map<String, SeatStatus> statusBySeatId = new ConcurrentHashMap<>();
        final Map<String, ReentrantLock> lockBySeatId = new ConcurrentHashMap<>();
        final Map<String, Instant> holdExpiryBySeatId = new ConcurrentHashMap<>();
        final Map<String, String> holderUserBySeatId = new ConcurrentHashMap<>();

        Show(String showId, Movie movie, Instant startTime, List<Seat> seats) {
            this.showId = showId;
            this.movie = movie;
            this.startTime = startTime;
            for (Seat seat : seats) {
                seatById.put(seat.seatId, seat);
                statusBySeatId.put(seat.seatId, SeatStatus.AVAILABLE);
                lockBySeatId.put(seat.seatId, new ReentrantLock());
            }
        }
    }

    static class Booking {
        final String bookingId;
        final String userId;
        final Show show;
        final List<String> seatIds;
        final double totalAmount;
        BookingStatus status = BookingStatus.PENDING;

        Booking(String bookingId, String userId, Show show, List<String> seatIds,
                double totalAmount) {
            this.bookingId = bookingId;
            this.userId = userId;
            this.show = show;
            this.seatIds = seatIds;
            this.totalAmount = totalAmount;
        }
    }

    // ─── Core service ───

    static class BookingService {
        static final long HOLD_SECONDS = 300;  // 5-min checkout window
        final Map<String, Booking> bookings = new ConcurrentHashMap<>();

        /**
         * Two-phase booking step 1: atomically hold all requested seats.
         * All-or-nothing — if any seat is taken, release the ones we grabbed and fail.
         */
        Booking holdSeats(String userId, Show show, List<String> seatIds) {
            // Sort seatIds to avoid deadlock between concurrent overlapping requests
            List<String> sortedIds = new ArrayList<>(seatIds);
            Collections.sort(sortedIds);

            List<String> held = new ArrayList<>();
            try {
                for (String seatId : sortedIds) {
                    if (!tryHoldSeat(show, seatId, userId)) {
                        throw new RuntimeException("Seat " + seatId + " unavailable");
                    }
                    held.add(seatId);
                }

                double total = sortedIds.stream()
                    .mapToDouble(id -> show.seatById.get(id).price).sum();
                String bookingId = "BKG-" + UUID.randomUUID().toString().substring(0, 8);
                Booking booking = new Booking(bookingId, userId, show, sortedIds, total);
                bookings.put(bookingId, booking);
                return booking;
            } catch (RuntimeException e) {
                // Rollback: release all seats we held
                for (String seatId : held) releaseHold(show, seatId, userId);
                throw e;
            }
        }

        /** Two-phase step 2: payment succeeded → mark all seats BOOKED. */
        Booking confirmBooking(String bookingId) {
            Booking booking = bookings.get(bookingId);
            if (booking == null) throw new RuntimeException("Not found: " + bookingId);
            if (booking.status != BookingStatus.PENDING) {
                throw new RuntimeException("Not pending: " + booking.status);
            }

            for (String seatId : booking.seatIds) {
                ReentrantLock lock = booking.show.lockBySeatId.get(seatId);
                lock.lock();
                try {
                    expireIfStale(booking.show, seatId);
                    if (booking.show.statusBySeatId.get(seatId) != SeatStatus.HELD) {
                        throw new RuntimeException("Hold expired: " + seatId);
                    }
                    booking.show.statusBySeatId.put(seatId, SeatStatus.BOOKED);
                    booking.show.holdExpiryBySeatId.remove(seatId);
                } finally {
                    lock.unlock();
                }
            }
            booking.status = BookingStatus.CONFIRMED;
            return booking;
        }

        /** Cancel (either pending or confirmed). */
        void cancelBooking(String bookingId) {
            Booking booking = bookings.get(bookingId);
            if (booking == null) return;
            for (String seatId : booking.seatIds) {
                ReentrantLock lock = booking.show.lockBySeatId.get(seatId);
                lock.lock();
                try {
                    booking.show.statusBySeatId.put(seatId, SeatStatus.AVAILABLE);
                    booking.show.holdExpiryBySeatId.remove(seatId);
                    booking.show.holderUserBySeatId.remove(seatId);
                } finally {
                    lock.unlock();
                }
            }
            booking.status = BookingStatus.CANCELLED;
        }

        // ─── Seat-level primitives ───

        private boolean tryHoldSeat(Show show, String seatId, String userId) {
            ReentrantLock lock = show.lockBySeatId.get(seatId);
            if (lock == null) return false;
            lock.lock();
            try {
                expireIfStale(show, seatId);
                if (show.statusBySeatId.get(seatId) != SeatStatus.AVAILABLE) return false;
                show.statusBySeatId.put(seatId, SeatStatus.HELD);
                show.holdExpiryBySeatId.put(seatId, Instant.now().plusSeconds(HOLD_SECONDS));
                show.holderUserBySeatId.put(seatId, userId);
                return true;
            } finally {
                lock.unlock();
            }
        }

        private void releaseHold(Show show, String seatId, String userId) {
            ReentrantLock lock = show.lockBySeatId.get(seatId);
            lock.lock();
            try {
                if (show.statusBySeatId.get(seatId) == SeatStatus.HELD
                        && userId.equals(show.holderUserBySeatId.get(seatId))) {
                    show.statusBySeatId.put(seatId, SeatStatus.AVAILABLE);
                    show.holdExpiryBySeatId.remove(seatId);
                    show.holderUserBySeatId.remove(seatId);
                }
            } finally {
                lock.unlock();
            }
        }

        /** Lazy expiry — called on every seat check. */
        private void expireIfStale(Show show, String seatId) {
            Instant expiry = show.holdExpiryBySeatId.get(seatId);
            if (expiry != null && Instant.now().isAfter(expiry)
                    && show.statusBySeatId.get(seatId) == SeatStatus.HELD) {
                show.statusBySeatId.put(seatId, SeatStatus.AVAILABLE);
                show.holdExpiryBySeatId.remove(seatId);
                show.holderUserBySeatId.remove(seatId);
            }
        }
    }

    // ─── Demo ───

    public static void main(String[] args) throws Exception {
        List<Seat> seats = new ArrayList<>();
        for (char row : new char[]{'A', 'B', 'C'}) {
            for (int i = 1; i <= 10; i++) {
                seats.add(new Seat(row + "-" + i, row == 'A' ? 15.0 : 10.0));
            }
        }
        Show show = new Show("S-1", new Movie("M-1", "Inception"), Instant.now(), seats);
        BookingService service = new BookingService();

        // Happy path
        Booking a = service.holdSeats("alice", show, Arrays.asList("A-1", "A-2"));
        System.out.println("Alice held " + a.seatIds + " total=$" + a.totalAmount);
        service.confirmBooking(a.bookingId);
        System.out.println("Status: " + a.status);

        // Double-booking blocked
        try {
            service.holdSeats("bob", show, Arrays.asList("A-1", "A-3"));
        } catch (Exception e) {
            System.out.println("Bob rejected: " + e.getMessage());
            System.out.println("A-3 still AVAILABLE (rollback)? "
                + (show.statusBySeatId.get("A-3") == SeatStatus.AVAILABLE));
        }

        // Concurrent race on same seat
        int N = 50;
        Thread[] threads = new Thread[N];
        int[] wins = {0};
        for (int i = 0; i < N; i++) {
            final String uid = "racer-" + i;
            threads[i] = new Thread(() -> {
                try {
                    Booking b = service.holdSeats(uid, show,
                        Collections.singletonList("B-5"));
                    service.confirmBooking(b.bookingId);
                    synchronized (wins) { wins[0]++; }
                } catch (Exception ignored) {}
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        System.out.println("50 threads on B-5 → winners: " + wins[0]
            + " (expected 1), seat status: " + show.statusBySeatId.get("B-5"));
    }
}
