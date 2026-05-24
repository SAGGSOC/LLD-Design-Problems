package hotel.strategy;

import hotel.model.DateRange;
import hotel.model.Room;

public interface PricingStrategy {
    /** Calculate total price for a room over the given date range. */
    double calculatePrice(Room room, DateRange dateRange);
}
