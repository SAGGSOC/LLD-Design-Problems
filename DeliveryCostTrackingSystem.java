import java.util.*;

/**
 * Delivery Cost Tracking System
 *
 * Features:
 * 1. Driver management
 * 2. Delivery cost tracking
 * 3. Paid / unpaid payment tracking
 * 4. Configurable pricing strategy
 * 5. Analytics:
 *      max active drivers in last 24 hrs
 */
public class DeliveryCostTrackingSystem {

    /*
     * =========================
     * PRICING STRATEGY
     * =========================
     */

    interface PricingStrategy {
        double calculateCost(Delivery delivery);
    }

    /*
     * Cost = duration * baseRate
     */
    static class PerMinutePricingStrategy
            implements PricingStrategy {

        private final double baseRate;

        public PerMinutePricingStrategy(double baseRate) {
            this.baseRate = baseRate;
        }

        @Override
        public double calculateCost(Delivery delivery) {

            long duration =
                    delivery.endTime - delivery.startTime;

            return duration * baseRate;
        }
    }

    /*
     * =========================
     * DELIVERY MODEL
     * =========================
     */

    static class Delivery {

        String driverId;

        long startTime;
        long endTime;

        double cost;

        boolean paid;

        public Delivery(String driverId,
                        long startTime,
                        long endTime) {

            this.driverId = driverId;
            this.startTime = startTime;
            this.endTime = endTime;

            this.paid = false;
        }

        @Override
        public String toString() {

            return "Delivery{" +
                    "driverId='" + driverId + '\'' +
                    ", startTime=" + startTime +
                    ", endTime=" + endTime +
                    ", cost=" + cost +
                    ", paid=" + paid +
                    '}';
        }
    }

    /*
     * =========================
     * MAIN SYSTEM
     * =========================
     */

    static class DeliveryTracker {

        /*
         * Drivers
         */
        private final Set<String> drivers;

        /*
         * Driver -> deliveries
         */
        private final Map<String, List<Delivery>>
                driverDeliveries;

        /*
         * All deliveries
         */
        private final List<Delivery> allDeliveries;

        /*
         * Unpaid deliveries ordered by endTime
         */
        private final PriorityQueue<Delivery>
                unpaidDeliveries;

        /*
         * Pricing
         */
        private final PricingStrategy pricingStrategy;

        /*
         * Aggregated costs
         */
        private double totalCost;

        private double paidCost;

        private double unpaidCost;

        public DeliveryTracker(
                PricingStrategy pricingStrategy) {

            this.pricingStrategy = pricingStrategy;

            this.drivers = new HashSet<>();

            this.driverDeliveries =
                    new HashMap<>();

            this.allDeliveries =
                    new ArrayList<>();

            this.unpaidDeliveries =
                    new PriorityQueue<>(
                            Comparator.comparingLong(
                                    d -> d.endTime
                            )
                    );

            this.totalCost = 0;
            this.paidCost = 0;
            this.unpaidCost = 0;
        }

        /*
         * =========================
         * DRIVER APIs
         * =========================
         */

        public void add_driver(String driverId) {

            if (drivers.contains(driverId)) {
                return;
            }

            drivers.add(driverId);

            driverDeliveries.put(
                    driverId,
                    new ArrayList<>()
            );
        }

        /*
         * =========================
         * DELIVERY APIs
         * =========================
         */

        public void add_delivery(String driverId,
                                 long startTime,
                                 long endTime) {

            if (!drivers.contains(driverId)) {
                throw new IllegalArgumentException(
                        "Driver does not exist"
                );
            }

            if (endTime < startTime) {
                throw new IllegalArgumentException(
                        "Invalid timestamps"
                );
            }

            Delivery delivery =
                    new Delivery(
                            driverId,
                            startTime,
                            endTime
                    );

            /*
             * Calculate cost
             */
            delivery.cost =
                    pricingStrategy.calculateCost(
                            delivery
                    );

            /*
             * Store
             */
            driverDeliveries
                    .get(driverId)
                    .add(delivery);

            allDeliveries.add(delivery);

            unpaidDeliveries.offer(delivery);

            /*
             * Update aggregates
             */
            totalCost += delivery.cost;

            unpaidCost += delivery.cost;
        }

        /*
         * =========================
         * COST APIs
         * =========================
         */

        public double get_total_cost() {
            return totalCost;
        }

        public double get_paid_cost() {
            return paidCost;
        }

        public double get_cost_to_be_paid() {
            return unpaidCost;
        }

        /*
         * =========================
         * PAYMENT APIs
         * =========================
         */

        public void pay_up_to_time(long upToTime) {

            while (!unpaidDeliveries.isEmpty()
                    && unpaidDeliveries.peek().endTime
                    <= upToTime) {

                Delivery delivery =
                        unpaidDeliveries.poll();

                if (!delivery.paid) {

                    delivery.paid = true;

                    paidCost += delivery.cost;

                    unpaidCost -= delivery.cost;
                }
            }
        }

        /*
         * =========================
         * ANALYTICS
         * =========================
         */

        /*
         * Maximum simultaneously active
         * drivers in last 24 hours
         */
        public int
        get_max_active_drivers_in_last_24_hours(
                long currentTime) {

            long windowStart =
                    currentTime - 24 * 60 * 60;

            List<long[]> events =
                    new ArrayList<>();

            for (Delivery delivery : allDeliveries) {

                /*
                 * Ignore deliveries completely
                 * outside the window
                 */
                if (delivery.endTime < windowStart
                        || delivery.startTime > currentTime) {

                    continue;
                }

                long effectiveStart =
                        Math.max(
                                delivery.startTime,
                                windowStart
                        );

                long effectiveEnd =
                        Math.min(
                                delivery.endTime,
                                currentTime
                        );

                /*
                 * +1 => driver becomes active
                 * -1 => driver becomes inactive
                 */
                events.add(
                        new long[]{
                                effectiveStart,
                                1
                        }
                );

                events.add(
                        new long[]{
                                effectiveEnd,
                                -1
                        }
                );
            }

            /*
             * Sort by time
             */
            events.sort((a, b) -> {

                if (a[0] == b[0]) {

                    /*
                     * start event first
                     */
                    return Long.compare(
                            b[1],
                            a[1]
                    );
                }

                return Long.compare(
                        a[0],
                        b[0]
                );
            });

            int activeDrivers = 0;

            int maxActiveDrivers = 0;

            for (long[] event : events) {

                activeDrivers += event[1];

                maxActiveDrivers =
                        Math.max(
                                maxActiveDrivers,
                                activeDrivers
                        );
            }

            return maxActiveDrivers;
        }

        /*
         * =========================
         * DEBUG HELPERS
         * =========================
         */

        public void print_all_deliveries() {

            for (Delivery d : allDeliveries) {
                System.out.println(d);
            }
        }
    }

    /*
     * =========================
     * MAIN METHOD
     * =========================
     */

    public static void main(String[] args) {

        /*
         * Base rate = 10/unit time
         */
        PricingStrategy pricingStrategy =
                new PerMinutePricingStrategy(10);

        DeliveryTracker tracker =
                new DeliveryTracker(pricingStrategy);

        /*
         * Add drivers
         */
        tracker.add_driver("D1");
        tracker.add_driver("D2");
        tracker.add_driver("D3");

        /*
         * Add deliveries
         */
        tracker.add_delivery(
                "D1",
                0,
                10
        ); // cost = 100

        tracker.add_delivery(
                "D2",
                5,
                25
        ); // cost = 200

        tracker.add_delivery(
                "D3",
                15,
                35
        ); // cost = 200

        /*
         * Print all deliveries
         */
        System.out.println(
                "===== ALL DELIVERIES ====="
        );

        tracker.print_all_deliveries();

        /*
         * Cost summary
         */
        System.out.println(
                "\n===== COST SUMMARY ====="
        );

        System.out.println(
                "Total Cost = "
                        + tracker.get_total_cost()
        );

        System.out.println(
                "Paid Cost = "
                        + tracker.get_paid_cost()
        );

        System.out.println(
                "Unpaid Cost = "
                        + tracker.get_cost_to_be_paid()
        );

        /*
         * Make payment
         */
        tracker.pay_up_to_time(20);

        System.out.println(
                "\n===== AFTER PAYMENT ====="
        );

        System.out.println(
                "Paid Cost = "
                        + tracker.get_paid_cost()
        );

        System.out.println(
                "Unpaid Cost = "
                        + tracker.get_cost_to_be_paid()
        );

        /*
         * Analytics
         */
        int maxActive =
                tracker
                        .get_max_active_drivers_in_last_24_hours(
                                40
                        );

        System.out.println(
                "\n===== ANALYTICS ====="
        );

        System.out.println(
                "Max Active Drivers = "
                        + maxActive
        );
    }
}