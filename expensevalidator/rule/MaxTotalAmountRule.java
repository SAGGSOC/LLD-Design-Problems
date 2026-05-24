package expensevalidator.rule;

import expensevalidator.model.Expense;
import expensevalidator.model.RuleViolation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rule: Total expense across all entries should not exceed a given limit.
 *
 * Example: "Total expense should not be > $175"
 * This is an AGGREGATE rule — it looks at the sum of all expenses.
 */
public class MaxTotalAmountRule implements Rule {

    private final String ruleId;
    private final double maxTotalAmount;

    public MaxTotalAmountRule(String ruleId, double maxTotalAmount) {
        this.ruleId = ruleId;
        this.maxTotalAmount = maxTotalAmount;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() { return "MaxTotalAmount"; }

    @Override
    public List<RuleViolation> evaluate(List<Expense> expenses) {
        double total = 0;
        for (Expense e : expenses) {
            total += e.getAmountInUsd();
        }

        if (total > maxTotalAmount) {
            RuleViolation violation = new RuleViolation(
                ruleId, getRuleName(), "ALL",
                String.format("Total expense $%.2f exceeds max allowed $%.2f", total, maxTotalAmount)
            );
            return Collections.singletonList(violation);
        }
        return Collections.emptyList();
    }
}
