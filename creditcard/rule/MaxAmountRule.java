package creditcard.rule;

import creditcard.model.Expense;
import creditcard.model.Violation;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Type 2: Maximum amount per individual expense.
 *
 * Example: "No individual expense over $250"
 */
public class MaxAmountRule implements Rule {

    private final String ruleId;
    private final double maxAmount;

    public MaxAmountRule(String ruleId, double maxAmount) {
        this.ruleId = ruleId;
        this.maxAmount = maxAmount;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() { return "MaxAmount($" + maxAmount + ")"; }

    @Override
    public List<Violation> evaluate(List<Expense> expenses) {
        List<Violation> violations = new ArrayList<>();

        for (Expense e : expenses) {
            if (e.getAmountUsd() > maxAmount) {
                violations.add(new Violation(
                    ruleId, getRuleName(), e.getExpenseId(), e.getTripId(),
                    String.format("$%.2f exceeds max individual expense $%.2f", e.getAmountUsd(), maxAmount)
                ));
            }
        }
        return violations;
    }
}
