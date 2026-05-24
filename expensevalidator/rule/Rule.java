package expensevalidator.rule;

import expensevalidator.model.Expense;
import expensevalidator.model.RuleViolation;

import java.util.List;

/**
 * Strategy interface for expense validation rules.
 *
 * Each rule implementation encapsulates its own validation logic.
 * Rules can be:
 *   - Per-expense (validate each expense independently)
 *   - Aggregate (validate across all expenses, e.g., total limit)
 */
public interface Rule {

    String getRuleId();

    String getRuleName();

    /**
     * Evaluate this rule against the list of expenses.
     * Returns a list of violations (empty if all pass).
     */
    List<RuleViolation> evaluate(List<Expense> expenses);
}
