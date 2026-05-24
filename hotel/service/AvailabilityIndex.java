package hotel.service;

import hotel.model.Booking;
import hotel.model.DateRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-room index of all active bookings. Enables fast overlap queries.
 *
 * Key: roomId → List of DateRanges (only for active bookings).
 *
 * Complexity:
 *   - isAvailable(room, range): O(B_room) where B_room = bookings for that room.
 *     For most rooms B_room is small (tens, not millions).
 *   - addBooking / removeBooking: O(B_room) amortized.
 *
 * For very large-scale systems: replace the List with an interval tree for O(log n)
 * overlap queries, or use a calendar/bitmap representation for finite planning horizons.
 */
public class AvailabilityIndex {

    private final Map<String, List<DateRange>> roomBookings = new ConcurrentHashMap<>();

    public synchronized boolean isAvailable(String roomId, DateRange requestedRange) {
        List<DateRange> existingRanges = roomBookings.get(roomId);
        if (existingRanges == null) return true;

        for (DateRange existing : existingRanges) {
            if (existing.overlaps(requestedRange)) {
                return false;
            }
        }
        return true;
    }

    public synchronized void reserve(String roomId, DateRange range) {
        roomBookings.computeIfAbsent(roomId, k -> new ArrayList<>()).add(range);
    }

    public synchronized void release(String roomId, DateRange range) {
        List<DateRange> ranges = roomBookings.get(roomId);
        if (ranges != null) {
            ranges.removeIf(existing ->
                existing.getCheckIn().equals(range.getCheckIn())
             && existing.getCheckOut().equals(range.getCheckOut()));
        }
    }

    /** Atomically check and reserve. Returns true if successful. */
    public synchronized boolean tryReserve(String roomId, DateRange range) {
        if (!isAvailable(roomId, range)) return false;
        reserve(roomId, range);
        return true;
    }
}
