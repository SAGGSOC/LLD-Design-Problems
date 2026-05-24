package deliverycost.service;

import deliverycost.exception.InvalidDeliveryException;
import deliverycost.model.Delivery;
import deliverycost.model.Payment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Payment Tracking Service.
 *
 * Tracks payments made up to a certain time and calculates outstanding balance.
 *
 * APIs:
 *   - payUpToTime(upToTime): Pay for all delivery time up to the given time.
 *   - getCostToBePaid(): Get remaining unpaid cost.
 */
public class PaymentService {

    private final CostCalculator costCalculator;
    private final List<Payment> payments;
    private final AtomicInteger paymentIdGen;

    // Tracks the last time up to which payment has been made
    private int lastPaidUpToTime;

    public PaymentService(CostCalculator costCalculator) {
        this.costCalculator = costCalculator;
        this.payments = new ArrayList<>();
        this.paymentIdGen = new AtomicInteger(1);
        this.lastPaidUpToTime = 0;
    }

    /**
     * Pay for all delivery cost incurred up to the given time.
     *
     * This calculates cost for deliveries (or portions of deliveries)
     * that fall within (lastPaidUpToTime, upToTime] and marks them as paid.
     *
     * @param upToTime the time up to which to pay
     * @param allDeliveries all deliveries in the system
     * @return the amount paid in this transaction
     */
    public double payUpToTime(int upToTime, List<Delivery> allDeliveries) {
        if (upToTime <= lastPaidUpToTime) {
            throw new InvalidDeliveryException(
                "upToTime must be greater than last paid time: " + lastPaidUpToTime);
        }

        // Get deliveries that overlap with (lastPaidUpToTime, upToTime]
        // Clip each delivery to this window
        List<Delivery> clippedDeliveries = clipDeliveries(allDeliveries, lastPaidUpToTime, upToTime);
        double amount = costCalculator.calculate(clippedDeliveries);

        String paymentId = "PAY-" + paymentIdGen.getAndIncrement();
        payments.add(new Payment(paymentId, upToTime, amount));
        lastPaidUpToTime = upToTime;

        return amount;
    }

    /**
     * Get the total cost that has not yet been paid.
     *
     * This is: totalCost - totalPaid
     * Alternatively: cost of all delivery time after lastPaidUpToTime.
     *
     * @param allDeliveries all deliveries in the system
     * @return outstanding unpaid cost
     */
    public double getCostToBePaid(List<Delivery> allDeliveries) {
        // Cost for everything after lastPaidUpToTime
        List<Delivery> unpaidDeliveries = clipDeliveries(allDeliveries, lastPaidUpToTime, Integer.MAX_VALUE);
        return costCalculator.calculate(unpaidDeliveries);
    }

    /**
     * Clips deliveries to the window (windowStart, windowEnd].
     * A delivery [s, e) is clipped to [max(s, windowStart), min(e, windowEnd)].
     * If the clipped interval is empty, the delivery is excluded.
     */
    private List<Delivery> clipDeliveries(List<Delivery> deliveries, int windowStart, int windowEnd) {
        List<Delivery> clipped = new ArrayList<>();
        for (Delivery d : deliveries) {
            int clippedStart = Math.max(d.getStartTime(), windowStart);
            int clippedEnd = Math.min(d.getEndTime(), windowEnd);
            if (clippedStart < clippedEnd) {
                clipped.add(new Delivery(d.getDeliveryId(), d.getDriverId(), clippedStart, clippedEnd));
            }
        }
        return clipped;
    }

    public int getLastPaidUpToTime() { return lastPaidUpToTime; }
    public List<Payment> getPayments() { return payments; }
    public double getTotalPaid() {
        return payments.stream().mapToDouble(Payment::getAmount).sum();
    }
}
