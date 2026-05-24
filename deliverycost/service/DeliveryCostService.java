package deliverycost.service;

import deliverycost.exception.DriverNotFoundException;
import deliverycost.exception.DuplicateDriverException;
import deliverycost.exception.InvalidDeliveryException;
import deliverycost.model.Delivery;
import deliverycost.model.Driver;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main facade exposing all APIs:
 *
 * Part 1 - Cost Calculation:
 *   - addDriver(driverId)
 *   - addDelivery(driverId, startTime, endTime)
 *   - getTotalCost()
 *
 * Part 2 - Payment Tracking:
 *   - payUpToTime(upToTime)
 *   - getCostToBePaid()
 *
 * Part 3 - Analytics:
 *   - getMaxActiveDriversInLast24Hours(currentTime)
 */
public class DeliveryCostService {

    private final Map<String, Driver> drivers;
    private final List<Delivery> allDeliveries;
    private final CostCalculator costCalculator;
    private final PaymentService paymentService;
    private final AnalyticsService analyticsService;
    private final AtomicInteger deliveryIdGen;

    public DeliveryCostService() {
        this.drivers = new HashMap<>();
        this.allDeliveries = new ArrayList<>();
        this.costCalculator = new CostCalculator();
        this.paymentService = new PaymentService(costCalculator);
        this.analyticsService = new AnalyticsService();
        this.deliveryIdGen = new AtomicInteger(1);
    }

    // ═══════════════════════════════════════════════
    // PART 1: Cost Calculation
    // ═══════════════════════════════════════════════

    public void addDriver(String driverId) {
        if (driverId == null || driverId.isEmpty()) {
            throw new InvalidDeliveryException("Driver ID cannot be null or empty");
        }
        if (drivers.containsKey(driverId)) {
            throw new DuplicateDriverException(driverId);
        }
        drivers.put(driverId, new Driver(driverId, driverId));
    }

    public void addDelivery(String driverId, int startTime, int endTime) {
        if (!drivers.containsKey(driverId)) {
            throw new DriverNotFoundException(driverId);
        }
        if (startTime >= endTime) {
            throw new InvalidDeliveryException("startTime must be less than endTime");
        }

        String deliveryId = "DEL-" + deliveryIdGen.getAndIncrement();
        Delivery delivery = new Delivery(deliveryId, driverId, startTime, endTime);

        Driver driver = drivers.get(driverId);
        driver.addDelivery(delivery);
        allDeliveries.add(delivery);
    }

    public double getTotalCost() {
        return costCalculator.calculate(allDeliveries);
    }

    // ═══════════════════════════════════════════════
    // PART 2: Payment Tracking
    // ═══════════════════════════════════════════════

    /**
     * Pay for all delivery cost incurred up to the given time.
     * Clips deliveries to the unpaid window and settles the cost.
     *
     * @return the amount paid in this transaction
     */
    public double payUpToTime(int upToTime) {
        return paymentService.payUpToTime(upToTime, allDeliveries);
    }

    /**
     * Get the remaining unpaid cost (all delivery time after last payment).
     */
    public double getCostToBePaid() {
        return paymentService.getCostToBePaid(allDeliveries);
    }

    // ═══════════════════════════════════════════════
    // PART 3: Analytics
    // ═══════════════════════════════════════════════

    /**
     * Returns the maximum number of concurrently active drivers
     * in the 24-hour window ending at currentTime.
     */
    public int getMaxActiveDriversInLast24Hours(int currentTime) {
        return analyticsService.getMaxActiveDriversInLast24Hours(currentTime, allDeliveries);
    }

    // ═══════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════

    public Driver getDriver(String driverId) {
        if (!drivers.containsKey(driverId)) {
            throw new DriverNotFoundException(driverId);
        }
        return drivers.get(driverId);
    }

    public List<Delivery> getAllDeliveries() {
        return Collections.unmodifiableList(allDeliveries);
    }

    public PaymentService getPaymentService() {
        return paymentService;
    }
}
