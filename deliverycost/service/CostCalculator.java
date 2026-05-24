package deliverycost.service;

import deliverycost.model.Delivery;
import deliverycost.model.TimeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calculates total delivery cost using a sweep-line algorithm.
 *
 * Pricing:
 * - Base rate: $5/hour per active driver
 * - Overlap penalty: $2/hour for each concurrent pair of drivers
 *   (i.e., K drivers active → K*(K-1)*$2/hour overlap cost)
 */
public class CostCalculator {

    private static final double BASE_RATE_PER_HOUR = 5.0;
    private static final double OVERLAP_PENALTY_PER_HOUR = 2.0;

    /**
     * Sweep-line approach:
     * 1. Create +1 event at each delivery start, -1 at each delivery end.
     * 2. Sort events by time (ends before starts on ties).
     * 3. Walk through events, accumulating cost for each interval.
     *
     * Time: O(N log N), Space: O(N)
     */
    public double calculate(List<Delivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) return 0.0;

        List<TimeEvent> events = new ArrayList<>();
        for (Delivery d : deliveries) {
            events.add(new TimeEvent(d.getStartTime(), 1));
            events.add(new TimeEvent(d.getEndTime(), -1));
        }

        Collections.sort(events);

        double totalCost = 0.0;
        int activeDrivers = 0;
        int prevTime = events.get(0).getTime();

        for (TimeEvent event : events) {
            int currTime = event.getTime();

            if (currTime > prevTime && activeDrivers > 0) {
                double hours = (currTime - prevTime) / 60.0;
                double baseCost = activeDrivers * BASE_RATE_PER_HOUR * hours;
                double overlapCost = activeDrivers * (activeDrivers - 1) * OVERLAP_PENALTY_PER_HOUR * hours;
                totalCost += baseCost + overlapCost;
            }

            activeDrivers += event.getDelta();
            prevTime = currTime;
        }

        return Math.round(totalCost * 100.0) / 100.0;
    }
}
