package moviebooking.model;

import moviebooking.enums.SeatStatus;
import moviebooking.exception.SeatUnavailableException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A Show is a specific movie screening at a specific screen and time.
 * It owns the seat availability state for that screening.
 *
 * Key concurrency design:
 *   - seatStatuses: per-seat status map (AVAILABLE / HELD / BOOKED)
 *   - seatLocks: per-seat fine-grained locks for atomic hold-and-verify
 *   - holdExpiry: when each held seat auto-releases back to AVAILABLE
 *
 * We use per-seat locks instead of a single show-level lock so that
 * two users picking different seats never block each other.
 */
public class Show {
    private final String showId;
    private final Movie movie;
    private final Screen screen;
    private final Instant startTime;
    private final Instant endTime;
    private final double priceMultiplier;  // prime-time shows cost more

    private final Map<String, SeatStatus> seatStatuses = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> seatLocks = new ConcurrentHashMap<>();
    private final Map<String, Instant> holdExpiry = new ConcurrentHashMap<>();
    private final Map<String, String> seatToUserId = new ConcurrentHashMap<>();
    // Reverse index for lookups
    private final Map<String, Seat> seatById = new ConcurrentHashMap<>();

    public Show(String showId, Movie movie, Screen screen, Instant startTime,
                Instant endTime, double priceMultiplier) {
        this.showId = showId;
        this.movie = movie;
        this.screen = screen;
        this.startTime = startTime;
        this.endTime = endTime;
        this.priceMultiplier = priceMultiplier;

        // Initialize every seat as AVAILABLE with its own lock
        for (Seat seat : screen.getSeats()) {
            seatStatuses.put(seat.getSeatId(), SeatStatus.AVAILABLE);
            seatLocks.put(seat.getSeatId(), new ReentrantLock());
            seatById.put(seat.getSeatId(), seat);
        }
    }

    public SeatStatus getSeatStatus(String seatId) {
        expireHoldIfStale(seatId);
        return seatStatuses.get(seatId);
    }

    public Seat getSeat(String seatId) {
        return seatById.get(seatId);
    }

    public List<Seat> getAvailableSeats() {
        List<Seat> available = new ArrayList<>();
        for (Seat seat : screen.getSeats()) {
            if (getSeatStatus(seat.getSeatId()) == SeatStatus.AVAILABLE) {
                available.add(seat);
            }
        }
        return available;
    }

    /**
     * Try to atomically hold a seat for a user.
     *
     * Returns true if successfully held.
     * Returns false if:
     *   - the seat is already BOOKED
     *   - the seat is HELD by someone else and not yet expired
     *
     * Uses per-seat ReentrantLock so different seats can be held in parallel
     * without blocking each other.
     */
    public boolean tryHoldSeat(String seatId, String userId, long holdDurationSeconds) {
        ReentrantLock lock = seatLocks.get(seatId);
        if (lock == null) return false;  // invalid seatId

        lock.lock();
        try {
            // Expire any stale hold
            expireHoldIfStale(seatId);

            SeatStatus currentStatus = seatStatuses.get(seatId);
            if (currentStatus != SeatStatus.AVAILABLE) {
                return false;
            }

            seatStatuses.put(seatId, SeatStatus.HELD);
            seatToUserId.put(seatId, userId);
            holdExpiry.put(seatId, Instant.now().plusSeconds(holdDurationSeconds));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Confirm a held seat as BOOKED. The user must be the same one who held it.
     */
    public void confirmSeat(String seatId, String userId) {
        ReentrantLock lock = seatLocks.get(seatId);
        lock.lock();
        try {
            expireHoldIfStale(seatId);
            SeatStatus current = seatStatuses.get(seatId);
            if (current != SeatStatus.HELD) {
                throw new SeatUnavailableException(
                    seatId + " is not held (status=" + current + ")");
            }
            if (!userId.equals(seatToUserId.get(seatId))) {
                throw new SeatUnavailableException(
                    seatId + " is held by a different user");
            }
            seatStatuses.put(seatId, SeatStatus.BOOKED);
            holdExpiry.remove(seatId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Release a held seat back to AVAILABLE (e.g., user cancels during checkout).
     */
    public void releaseSeat(String seatId, String userId) {
        ReentrantLock lock = seatLocks.get(seatId);
        lock.lock();
        try {
            SeatStatus current = seatStatuses.get(seatId);
            if (current == SeatStatus.HELD && userId.equals(seatToUserId.get(seatId))) {
                seatStatuses.put(seatId, SeatStatus.AVAILABLE);
                seatToUserId.remove(seatId);
                holdExpiry.remove(seatId);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Release a booked seat (e.g., post-payment refund).
     */
    public void releaseBookedSeat(String seatId) {
        ReentrantLock lock = seatLocks.get(seatId);
        lock.lock();
        try {
            seatStatuses.put(seatId, SeatStatus.AVAILABLE);
            seatToUserId.remove(seatId);
            holdExpiry.remove(seatId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Auto-release stale holds. Called lazily on every status check.
     * In production, also run a cleanup job every 10s for active cleanup.
     */
    private void expireHoldIfStale(String seatId) {
        Instant expiry = holdExpiry.get(seatId);
        if (expiry != null && Instant.now().isAfter(expiry)) {
            ReentrantLock lock = seatLocks.get(seatId);
            if (lock.tryLock()) {  // avoid deadlock if caller already holds lock
                try {
                    // Double-check inside lock (another thread may have already expired it)
                    Instant exp = holdExpiry.get(seatId);
                    if (exp != null && Instant.now().isAfter(exp)
                        && seatStatuses.get(seatId) == SeatStatus.HELD) {
                        seatStatuses.put(seatId, SeatStatus.AVAILABLE);
                        seatToUserId.remove(seatId);
                        holdExpiry.remove(seatId);
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    public double calculatePrice(Seat seat) {
        return seat.getBasePrice() * priceMultiplier;
    }

    public String getShowId()       { return showId; }
    public Movie getMovie()         { return movie; }
    public Screen getScreen()       { return screen; }
    public Instant getStartTime()   { return startTime; }
    public Instant getEndTime()     { return endTime; }
    public double getPriceMultiplier() { return priceMultiplier; }
}
