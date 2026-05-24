package carrental.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Half-open date range [pickupDate, returnDate).
 * pickupDate inclusive, returnDate exclusive — matches rental industry convention
 * where you're charged for the nights between pickup and return.
 */
public class DateRange {
    private final LocalDate pickupDate;
    private final LocalDate returnDate;

    public DateRange(LocalDate pickupDate, LocalDate returnDate) {
        if (!returnDate.isAfter(pickupDate)) {
            throw new IllegalArgumentException(
                "Return date must be after pickup date");
        }
        this.pickupDate = pickupDate;
        this.returnDate = returnDate;
    }

    public long getDays() {
        return ChronoUnit.DAYS.between(pickupDate, returnDate);
    }

    public boolean overlaps(DateRange other) {
        return pickupDate.isBefore(other.returnDate)
            && other.pickupDate.isBefore(returnDate);
    }

    public LocalDate getPickupDate() { return pickupDate; }
    public LocalDate getReturnDate() { return returnDate; }

    @Override
    public String toString() {
        return pickupDate + " → " + returnDate + " (" + getDays() + " days)";
    }
}
