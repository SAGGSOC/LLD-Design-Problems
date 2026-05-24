package expensevalidator.rule;

import expensevalidator.model.Expense;
import expensevalidator.model.RuleViolation;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule: A specific expense type is completely blocked (not allowed).
 *
 * Example: "Entertainment expense type should not be charged"
 * This is a PER-EXPENSE rule filtered by expense type.
 */
public class BlockedExpenseTypeRule implements Rule {

    private final String ruleId;
    private final String blockedType;

    public BlockedExpenseTypeRule(String ruleId, String blockedType) {
        this.ruleId = ruleId;
        this.blockedType = blockedType;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() { return "BlockedExpenseType(" + blockedType + ")"; }

    @Override
    public List<RuleViolation> evaluate(List<Expense> expenses) {
        List<RuleViolation> violations = new ArrayList<>();

        for (Expense e : expenses) {
            if (e.getExpenseType().equalsIgnoreCase(blockedType)) {
                violations.add(new RuleViolation(
                    ruleId, getRuleName(), e.getExpenseId(),
                    String.format("Expense type '%s' is not allowed", blockedType)
                ));
            }
        }
        return violations;
    }
}
