package creditcard.rule;

import creditcard.model.Expense;
import creditcard.model.Violation;

import java.util.List;

/**
 * Strategy interface for all credit card policy rules.
 *
 * Rules fall into two categories:
 *   1. Per-expense rules: evaluate each expense independently
 *      (BanRule, MaxAmountRule, VendorTypeLimitRule)
 *
 *   2. Aggregate rules: evaluate across groups of expenses (by trip, by type, etc.)
 *      (TripTotalLimitRule, ExpenseTypeAggregationRule)
 *
 * All rules receive the full list and decide internally how to group/filter.
 */
public interface Rule {

    String getRuleId();

    String getRuleName();

    /**
     * Evaluate this rule against the full list of expenses.
     * Returns violations (empty list if all pass).
     */
    List<Violation> evaluate(List<Expense> expenses);
}
