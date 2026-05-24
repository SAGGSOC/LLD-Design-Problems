package creditcard.rule;

import creditcard.model.Expense;
import creditcard.model.Violation;

import java.util.*;

/**
 * Rule Type 3: Trip total limit.
 * Sum of all expenses for a trip cannot exceed a certain amount.
 *
 * Example: "A trip cannot exceed $2000 in total expenses"
 *
 * Groups expenses by trip_id, sums amounts, flags trips over limit.
 */
public class TripTotalLimitRule implements Rule {

    private final String ruleId;
    private final double maxTripTotal;

    public TripTotalLimitRule(String ruleId, double maxTripTotal) {
        this.ruleId = ruleId;
        this.maxTripTotal = maxTripTotal;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() { return "TripTotalLimit($" + maxTripTotal + ")"; }

    @Override
    public List<Violation> evaluate(List<Expense> expenses) {
        // Group by trip_id
        Map<String, Double> tripTotals = new HashMap<>();
        for (Expense e : expenses) {
            String tripId = e.getTripId();
            if (!tripId.isEmpty()) {
                tripTotals.merge(tripId, e.getAmountUsd(), Double::sum);
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Double> entry : tripTotals.entrySet()) {
            if (entry.getValue() > maxTripTotal) {
                violations.add(new Violation(
                    ruleId, getRuleName(), "ALL", entry.getKey(),
                    String.format("Trip total $%.2f exceeds limit $%.2f", entry.getValue(), maxTripTotal)
                ));
            }
        }
        return violations;
    }
}
