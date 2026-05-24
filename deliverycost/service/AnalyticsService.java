package deliverycost.service;

import deliverycost.model.Delivery;
import deliverycost.model.TimeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analytics Service.
 *
 * APIs:
 *   - getMaxActiveDriversInLast24Hours(currentTime):
 *     Returns the maximum number of concurrently active drivers
 *     in the window [currentTime - 24*60, currentTime].
 */
public class AnalyticsService {

    private static final int MINUTES_IN_24_HOURS = 24 * 60; // 1440

    /**
     * Sweep-line to find peak concurrency in the last 24 hours.
     *
     * 1. Filter deliveries that overlap with [currentTime - 1440, currentTime].
     * 2. Clip them to the window.
     * 3. Sweep-line to find max concurrent active drivers.
     *
     * Time: O(N log N), Space: O(N)
     */
    public int getMaxActiveDriversInLast24Hours(int currentTime, List<Delivery> allDeliveries) {
        int windowStart = currentTime - MINUTES_IN_24_HOURS;
        int windowEnd = currentTime;

        // Build events only for deliveries overlapping the 24h window
        List<TimeEvent> events = new ArrayList<>();
        for (Delivery d : allDeliveries) {
            int clippedStart = Math.max(d.getStartTime(), windowStart);
            int clippedEnd = Math.min(d.getEndTime(), windowEnd);
            if (clippedStart < clippedEnd) {
                events.add(new TimeEvent(clippedStart, 1));
                events.add(new TimeEvent(clippedEnd, -1));
            }
        }

        if (events.isEmpty()) return 0;

        Collections.sort(events);

        int maxActive = 0;
        int currentActive = 0;

        for (TimeEvent event : events) {
            currentActive += event.getDelta();
            maxActive = Math.max(maxActive, currentActive);
        }

        return maxActive;
    }
}
