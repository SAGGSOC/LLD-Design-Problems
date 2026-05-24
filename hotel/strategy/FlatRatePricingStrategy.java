package hotel.strategy;

import hotel.model.DateRange;
import hotel.model.Room;

/**
 * Simple pricing: baseRatePerNight × number of nights.
 */
public class FlatRatePricingStrategy implements PricingStrategy {

    @Override
    public double calculatePrice(Room room, DateRange dateRange) {
        return room.getBaseRatePerNight() * dateRange.getNights();
    }
}
