import java.util.*;

/**
 * Delivery Cost Tracking System (Simplified - Interview Style)
 *
 * Part 1: Cost Calculation
 *   - add_driver(driverId)
 *   - add_delivery(driverId, startTime, endTime)
 *   - get_total_cost()
 *
 * Part 2: Payment Tracking
 *   - pay_up_to_time(upToTime)
 *   - get_cost_to_be_paid()
 *
 * Part 3: Analytics
 *   - get_max_active_drivers_in_last_24_hours(currentTime)
 *
 * Pricing:
 *   - $5/hour per active driver
 *   - $2/hour overlap penalty per concurrent pair
 *   - K drivers active → cost/hour = K*5 + K*(K-1)*2
 */
public class DeliveryCostTracker {

    private static final double BASE_RATE = 5.0;       // per hour per driver
    private static final double OVERLAP_RATE = 2.0;    // per hour per concurrent pair
    private static final int MINUTES_IN_24H = 24 * 60;

    private final Set<String> drivers;
    private final List<int[]> deliveries;  // each: [startTime, endTime]
    private int lastPaidTime;              // watermark for payments

    public DeliveryCostTracker() {
        this.drivers = new HashSet<>();
        this.deliveries = new ArrayList<>();
        this.lastPaidTime = 0;
    }

    // ═══════════════════════════════════════════════
    // PART 1: Cost Calculation
    // ═══════════════════════════════════════════════

    public void addDriver(String driverId) {
        if (!drivers.add(driverId)) {
            throw new IllegalArgumentException("Driver already exists: " + driverId);
        }
    }

    public void addDelivery(String driverId, int startTime, int endTime) {
        if (!drivers.contains(driverId)) throw new IllegalArgumentException("Driver not found");
        if (startTime >= endTime) throw new IllegalArgumentException("Invalid time range");
        deliveries.add(new int[]{startTime, endTime});
    }

    public double getTotalCost() {
        return computeCost(deliveries, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    // ═══════════════════════════════════════════════
    // PART 2: Payment Tracking
    // ═══════════════════════════════════════════════

    /**
     * Pay for all delivery time in (lastPaidTime, upToTime].
     * Returns amount paid.
     */
    public double payUpToTime(int upToTime) {
        if (upToTime <= lastPaidTime) throw new IllegalArgumentException("Already paid up to this time");
        double cost = computeCost(deliveries, lastPaidTime, upToTime);
        lastPaidTime = upToTime;
        return cost;
    }

    /**
     * Get remaining unpaid cost (everything after lastPaidTime).
     */
    public double getCostToBePaid() {
        return computeCost(deliveries, lastPaidTime, Integer.MAX_VALUE);
    }

    // ═══════════════════════════════════════════════
    // PART 3: Analytics
    // ═══════════════════════════════════════════════

    /**
     * Max concurrent active drivers in [currentTime - 24h, currentTime].
     */
    public int getMaxActiveDriversInLast24Hours(int currentTime) {
        int windowStart = currentTime - MINUTES_IN_24H;
        int windowEnd = currentTime;

        // Build sweep-line events clipped to window
        List<int[]> events = new ArrayList<>();
        for (int[] d : deliveries) {
            int s = Math.max(d[0], windowStart);
            int e = Math.min(d[1], windowEnd);
            if (s < e) {
                events.add(new int[]{s, 1});
                events.add(new int[]{e, -1});
            }
        }
        events.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);

        int max = 0, active = 0;
        for (int[] ev : events) {
            active += ev[1];
            max = Math.max(max, active);
        }
        return max;
    }

    // ═══════════════════════════════════════════════
    // Core: Sweep-line cost computation
    // ═══════════════════════════════════════════════

    /**
     * Compute cost for all deliveries clipped to (windowStart, windowEnd].
     *
     * Sweep-line: sort start/end events, walk through intervals,
     * charge based on number of concurrent drivers.
     */
    private double computeCost(List<int[]> deliveries, int windowStart, int windowEnd) {
        List<int[]> events = new ArrayList<>();
        for (int[] d : deliveries) {
            int s = Math.max(d[0], windowStart);
            int e = Math.min(d[1], windowEnd);
            if (s < e) {
                events.add(new int[]{s, 1});   // +1 at start
                events.add(new int[]{e, -1});  // -1 at end
            }
        }
        if (events.isEmpty()) return 0.0;

        events.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);

        double totalCost = 0.0;
        int active = 0;
        int prevTime = events.get(0)[0];

        for (int[] ev : events) {
            int currTime = ev[0];
            if (currTime > prevTime && active > 0) {
                double hours = (currTime - prevTime) / 60.0;
                totalCost += active * BASE_RATE * hours;
                totalCost += active * (active - 1) * OVERLAP_RATE * hours;
            }
            active += ev[1];
            prevTime = currTime;
        }
        return Math.round(totalCost * 100.0) / 100.0;
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        DeliveryCostTracker tracker = new DeliveryCostTracker();

        tracker.addDriver("D1");
        tracker.addDriver("D2");
        tracker.addDriver("D3");

        tracker.addDelivery("D1", 0, 60);    // 1 hour
        tracker.addDelivery("D2", 30, 90);   // overlaps D1 from 30-60
        tracker.addDelivery("D3", 120, 180); // no overlap

        // Part 1
        System.out.println("═══ PART 1: Cost ═══");
        System.out.println("Total: $" + tracker.getTotalCost()); // $17.0

        // Part 2
        System.out.println("\n═══ PART 2: Payments ═══");
        System.out.println("Pay up to t=60: $" + tracker.payUpToTime(60));   // $9.5
        System.out.println("Remaining: $" + tracker.getCostToBePaid());       // $7.5
        System.out.println("Pay up to t=100: $" + tracker.payUpToTime(100)); // $2.5
        System.out.println("Remaining: $" + tracker.getCostToBePaid());       // $5.0

        // Part 3
        System.out.println("\n═══ PART 3: Analytics ═══");
        System.out.println("Max active (last 24h from t=90): " + tracker.getMaxActiveDriversInLast24Hours(90));   // 2
        System.out.println("Max active (last 24h from t=180): " + tracker.getMaxActiveDriversInLast24Hours(180)); // 2

        tracker.addDelivery("D1", 200, 260);
        tracker.addDelivery("D2", 210, 270);
        tracker.addDelivery("D3", 220, 280);
        System.out.println("Max active (last 24h from t=280): " + tracker.getMaxActiveDriversInLast24Hours(280)); // 3
    }
}
