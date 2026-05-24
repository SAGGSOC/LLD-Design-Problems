package carrental.service;

import carrental.model.DateRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-vehicle index of reserved date ranges.
 * Enables fast overlap queries for "is this car free these dates?"
 *
 * Trade-off analysis for 1K vehicles × 30 bookings each = 30K total ranges:
 *   - List<DateRange> with linear overlap check: O(B_vehicle) per query,
 *     typically < 1ms even with hundreds of bookings per vehicle.
 *   - Interval tree: O(log B_vehicle) — overkill until you have millions of bookings
 *     per vehicle, which never happens for a single car.
 *
 * tryReserve() is atomic — checks availability and marks reserved in a single lock.
 * This prevents the "two customers book the last car for the same weekend" race.
 */
public class AvailabilityIndex {
    private final Map<String, List<DateRange>> reservedRangesByVehicle = new ConcurrentHashMap<>();

    public synchronized boolean isAvailable(String vehicleId, DateRange requestedRange) {
        List<DateRange> existing = reservedRangesByVehicle.get(vehicleId);
        if (existing == null) return true;
        for (DateRange range : existing) {
            if (range.overlaps(requestedRange)) return false;
        }
        return true;
    }

    public synchronized void reserve(String vehicleId, DateRange range) {
        reservedRangesByVehicle
            .computeIfAbsent(vehicleId, k -> new ArrayList<>())
            .add(range);
    }

    public synchronized void release(String vehicleId, DateRange range) {
        List<DateRange> ranges = reservedRangesByVehicle.get(vehicleId);
        if (ranges != null) {
            ranges.removeIf(existing ->
                existing.getPickupDate().equals(range.getPickupDate())
             && existing.getReturnDate().equals(range.getReturnDate()));
        }
    }

    /** Atomic check-and-reserve. Returns true on success. */
    public synchronized boolean tryReserve(String vehicleId, DateRange range) {
        if (!isAvailable(vehicleId, range)) return false;
        reserve(vehicleId, range);
        return true;
    }
}
