package carrental.strategy;

import carrental.model.DateRange;
import carrental.model.Vehicle;

/**
 * Standard pricing:
 *   base = vehicle.dailyRate × days
 *   late fee = $15 per hour late
 *   one-way fee = $50 flat if pickup store != return store
 */
public class StandardPricingStrategy implements PricingStrategy {

    private static final double LATE_FEE_PER_HOUR = 15.0;
    private static final double ONE_WAY_FEE = 50.0;

    @Override
    public double estimatePrice(Vehicle vehicle, DateRange dateRange) {
        return round(vehicle.getDailyRate() * dateRange.getDays());
    }

    @Override
    public double calculateLateReturnFee(Vehicle vehicle, long hoursLate) {
        if (hoursLate <= 0) return 0;
        return round(hoursLate * LATE_FEE_PER_HOUR);
    }

    @Override
    public double calculateOneWayFee(String pickupStoreId, String returnStoreId) {
        return pickupStoreId.equals(returnStoreId) ? 0.0 : ONE_WAY_FEE;
    }

    private double round(double amount) {
        return Math.round(amount * 100) / 100.0;
    }
}
