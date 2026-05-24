package parkinglot.service;

import parkinglot.model.Payment;
import parkinglot.model.Ticket;

/**
 * Delegates fee calculation to a pluggable FeeStrategy.
 * Swap strategies without touching any caller code.
 */
public class FeeCalculator {
    private final FeeStrategy feeStrategy;

    public FeeCalculator(FeeStrategy feeStrategy) {
        this.feeStrategy = feeStrategy;
    }

    // Default: hourly billing
    public FeeCalculator() {
        this(new HourlyFeeStrategy());
    }

    public Payment calculate(Ticket ticket) {
        return feeStrategy.calculate(ticket);
    }
}
