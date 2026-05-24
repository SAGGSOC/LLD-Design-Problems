package creditcard.rule;

import creditcard.model.Expense;
import creditcard.model.Violation;

import java.util.*;

/**
 * Rule Type 4: Expense type aggregation per trip.
 * Sum of expenses of a specific type within a trip cannot exceed a limit.
 *
 * Example: "Total meal expenses cannot exceed $200 per trip"
 *
 * Groups expenses by trip_id, filters by expense_type, sums, and flags.
 */
public class ExpenseTypeAggregationRule implements Rule {

    private final String ruleId;
    private final String expenseType;
    private final double maxAmount;

    public ExpenseTypeAggregationRule(String ruleId, String expenseType, double maxAmount) {
        this.ruleId = ruleId;
        this.expenseType = expenseType;
        this.maxAmount = maxAmount;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() {
        return "ExpenseTypeAggregation(" + expenseType + ", $" + maxAmount + "/trip)";
    }

    @Override
    public List<Violation> evaluate(List<Expense> expenses) {
        // Group by trip_id, sum only matching expense_type
        Map<String, Double> tripTypeTotals = new HashMap<>();

        for (Expense e : expenses) {
            if (e.getExpenseType().equalsIgnoreCase(expenseType) && !e.getTripId().isEmpty()) {
                tripTypeTotals.merge(e.getTripId(), e.getAmountUsd(), Double::sum);
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Double> entry : tripTypeTotals.entrySet()) {
            if (entry.getValue() > maxAmount) {
                violations.add(new Violation(
                    ruleId, getRuleName(), "ALL", entry.getKey(),
                    String.format("Trip '%s' total %s expenses $%.2f exceeds limit $%.2f",
                        entry.getKey(), expenseType, entry.getValue(), maxAmount)
                ));
            }
        }
        return violations;
    }
}
