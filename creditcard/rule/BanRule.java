package creditcard.rule;

import creditcard.model.Expense;
import creditcard.model.Violation;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Type 1: Ban rules.
 * Blocks expenses based on a specific field matching a banned value.
 *
 * Examples:
 *   - Ban expense_type == "airfare"
 *   - Ban expense_type == "entertainment"
 *   - Ban vendor_type == "casino"
 *   - Ban vendor_name == "Some Blacklisted Vendor"
 *
 * Configurable by field name and banned value.
 */
public class BanRule implements Rule {

    private final String ruleId;
    private final String fieldName;    // "expense_type", "vendor_type", "vendor_name"
    private final String bannedValue;

    public BanRule(String ruleId, String fieldName, String bannedValue) {
        this.ruleId = ruleId;
        this.fieldName = fieldName;
        this.bannedValue = bannedValue;
    }

    @Override
    public String getRuleId() { return ruleId; }

    @Override
    public String getRuleName() { return "Ban(" + fieldName + "=" + bannedValue + ")"; }

    @Override
    public List<Violation> evaluate(List<Expense> expenses) {
        List<Violation> violations = new ArrayList<>();

        for (Expense e : expenses) {
            String value = e.getRawData().getOrDefault(fieldName, "");
            if (value.equalsIgnoreCase(bannedValue)) {
                violations.add(new Violation(
                    ruleId, getRuleName(), e.getExpenseId(), e.getTripId(),
                    String.format("'%s=%s' is not allowed", fieldName, bannedValue)
                ));
            }
        }
        return violations;
    }
}
