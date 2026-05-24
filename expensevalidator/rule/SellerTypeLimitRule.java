package expensevalidator.rule;

import expensevalidator.model.Expense;
import expensevalidator.model.RuleViolation;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule: A specific seller type should not have individual expense more than a limit.
 *
 * Example: "Seller type 'restaurant' should not have expense more than $45"
 * This is a PER-EXPENSE rule filtered by seller type.
 */
public class SellerTypeLimitRule implements Rule {

    private final String ruleId;
    private final String sellerType;
    private final double maxAmount;

    public SellerTypeLimitRule(String ruleId, String sellerType, double maxAmount) {
        this.ruleId = ruleId;
        this.sellerType = sellerType;
        this.maxAmount = maxAmount;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() { return "SellerTypeLimit(" + sellerType + ")"; }

    @Override
    public List<RuleViolation> evaluate(List<Expense> expenses) {
        List<RuleViolation> violations = new ArrayList<>();

        for (Expense e : expenses) {
            if (e.getSellerType().equalsIgnoreCase(sellerType) && e.getAmountInUsd() > maxAmount) {
                violations.add(new RuleViolation(
                    ruleId, getRuleName(), e.getExpenseId(),
                    String.format("Seller type '%s' expense $%.2f exceeds limit $%.2f",
                        sellerType, e.getAmountInUsd(), maxAmount)
                ));
            }
        }
        return violations;
    }
}
