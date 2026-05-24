package hotel.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Half-open date range: [checkIn, checkOut).
 * checkIn is inclusive, checkOut is exclusive — matches hotel industry convention
 * where you stay the nights from checkIn up to (but not including) checkOut.
 */
public class DateRange {
    private final LocalDate checkIn;
    private final LocalDate checkOut;

    public DateRange(LocalDate checkIn, LocalDate checkOut) {
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("Check-out must be after check-in");
        }
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    public long getNights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    /** Two ranges overlap if they share at least one night. */
    public boolean overlaps(DateRange other) {
        return this.checkIn.isBefore(other.checkOut)
            && other.checkIn.isBefore(this.checkOut);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(checkIn) && date.isBefore(checkOut);
    }

    public LocalDate getCheckIn()  { return checkIn; }
    public LocalDate getCheckOut() { return checkOut; }

    @Override
    public String toString() {
        return checkIn + " → " + checkOut + " (" + getNights() + " nights)";
    }
}
