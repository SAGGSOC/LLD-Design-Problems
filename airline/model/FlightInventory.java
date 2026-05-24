package airline.model;

import airline.enums.FareClass;
import airline.exception.NoSeatsAvailableException;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks available seat counts per fare class for a single flight.
 *
 * Concurrency model:
 *   Uses AtomicInteger per fare class for lock-free reservation.
 *   reserveSeat() uses compare-and-set loop to atomically decrement only if seats available.
 *   This is the classic "last seat race" problem — two users racing for seat 1.
 *   Only one CAS wins; the other retries or sees zero and fails.
 *
 * No seat-map precision here — we track counts, not individual seats.
 * Specific seat assignment happens at check-in (see Booking.assignedSeat).
 */
public class FlightInventory {
    private final Map<FareClass, AtomicInteger> availableSeats = new EnumMap<>(FareClass.class);
    private final Map<FareClass, Integer> totalSeats = new EnumMap<>(FareClass.class);
    private final Map<FareClass, Double> fareByClass = new EnumMap<>(FareClass.class);

    public FlightInventory(Map<FareClass, Integer> seatCounts,
                           Map<FareClass, Double> fares) {
        for (Map.Entry<FareClass, Integer> entry : seatCounts.entrySet()) {
            availableSeats.put(entry.getKey(), new AtomicInteger(entry.getValue()));
            totalSeats.put(entry.getKey(), entry.getValue());
        }
        fareByClass.putAll(fares);
    }

    public int getAvailableCount(FareClass fareClass) {
        AtomicInteger count = availableSeats.get(fareClass);
        return count == null ? 0 : count.get();
    }

    public int getTotalCount(FareClass fareClass) {
        return totalSeats.getOrDefault(fareClass, 0);
    }

    public double getFare(FareClass fareClass) {
        return fareByClass.getOrDefault(fareClass, 0.0);
    }

    /**
     * Atomically decrement seat count if seats are available.
     * Returns true if a seat was reserved, false if sold out.
     *
     * Uses CAS (compare-and-set) loop — lock-free, no blocking between threads.
     */
    public boolean reserveSeat(FareClass fareClass) {
        AtomicInteger count = availableSeats.get(fareClass);
        if (count == null) {
            throw new NoSeatsAvailableException("Fare class not offered: " + fareClass);
        }

        while (true) {
            int current = count.get();
            if (current <= 0) return false;  // sold out
            if (count.compareAndSet(current, current - 1)) {
                return true;  // we got a seat
            }
            // CAS failed — another thread beat us. Retry with latest value.
        }
    }

    /** Release a seat — used on cancellation. */
    public void releaseSeat(FareClass fareClass) {
        AtomicInteger count = availableSeats.get(fareClass);
        if (count != null) {
            count.incrementAndGet();
            // Note: this could theoretically exceed totalSeats if misused.
            // In production, guard with max = totalSeats[fareClass].
        }
    }
}
