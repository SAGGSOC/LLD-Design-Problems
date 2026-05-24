package carrental.strategy;

import carrental.model.DateRange;
import carrental.model.Vehicle;

public interface PricingStrategy {
    /** Estimated cost at reservation time (before pickup). */
    double estimatePrice(Vehicle vehicle, DateRange dateRange);

    /** Late-return surcharge. hoursLate can be negative (returned early → 0). */
    double calculateLateReturnFee(Vehicle vehicle, long hoursLate);

    /** Cross-store drop-off fee if pickup store != return store. */
    double calculateOneWayFee(String pickupStoreId, String returnStoreId);
}
