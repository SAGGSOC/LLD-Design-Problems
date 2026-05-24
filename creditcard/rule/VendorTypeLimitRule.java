package creditcard.rule;

import creditcard.model.Expense;
import creditcard.model.Violation;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Type 5: Vendor type limit.
 * A single expense at a specific vendor_type cannot exceed a certain amount.
 *
 * Example: "No expense at a restaurant can exceed $75"
 */
public class VendorTypeLimitRule implements Rule {

    private final String ruleId;
    private final String vendorType;
    private final double maxAmount;

    public VendorTypeLimitRule(String ruleId, String vendorType, double maxAmount) {
        this.ruleId = ruleId;
        this.vendorType = vendorType;
        this.maxAmount = maxAmount;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() {
        return "VendorTypeLimit(" + vendorType + ", $" + maxAmount + ")";
    }

    @Override
    public List<Violation> evaluate(List<Expense> expenses) {
        List<Violation> violations = new ArrayList<>();

        for (Expense e : expenses) {
            if (e.getVendorType().equalsIgnoreCase(vendorType) && e.getAmountUsd() > maxAmount) {
                violations.add(new Violation(
                    ruleId, getRuleName(), e.getExpenseId(), e.getTripId(),
                    String.format("$%.2f at vendor_type '%s' exceeds limit $%.2f",
                        e.getAmountUsd(), vendorType, maxAmount)
                ));
            }
        }
        return violations;
    }
}
