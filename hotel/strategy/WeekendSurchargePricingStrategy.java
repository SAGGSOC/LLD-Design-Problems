package hotel.strategy;

import hotel.model.DateRange;
import hotel.model.Room;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Weekdays at base rate, weekends (Fri/Sat/Sun nights) at 1.5x base rate.
 * Demonstrates how the strategy pattern makes dynamic pricing extensible.
 */
public class WeekendSurchargePricingStrategy implements PricingStrategy {

    private static final double WEEKEND_MULTIPLIER = 1.5;

    @Override
    public double calculatePrice(Room room, DateRange dateRange) {
        double total = 0;
        LocalDate currentNight = dateRange.getCheckIn();

        while (currentNight.isBefore(dateRange.getCheckOut())) {
            DayOfWeek dayOfWeek = currentNight.getDayOfWeek();
            boolean isWeekendNight = dayOfWeek == DayOfWeek.FRIDAY
                                  || dayOfWeek == DayOfWeek.SATURDAY
                                  || dayOfWeek == DayOfWeek.SUNDAY;

            double nightRate = room.getBaseRatePerNight()
                * (isWeekendNight ? WEEKEND_MULTIPLIER : 1.0);
            total += nightRate;
            currentNight = currentNight.plusDays(1);
        }
        return Math.round(total * 100) / 100.0;
    }
}
