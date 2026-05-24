package parkinglot.service;

import parkinglot.model.Payment;
import parkinglot.model.Ticket;

/**
 * Strategy interface for fee calculation.
 * Implement for different pricing models:
 * - HourlyFeeStrategy (current default)
 * - TimeBandFeeStrategy (peak/off-peak rates)
 * - FlatRateFeeStrategy (daily flat rate)
 * - SubscriptionFeeStrategy (monthly pass holders)
 */
public interface FeeStrategy {
    Payment calculate(Ticket ticket);
}
