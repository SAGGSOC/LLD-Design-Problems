package creditcard.model;

/**
 * Represents a rule violation.
 *
 * Contains:
 * - Which rule was violated
 * - Which expense(s) triggered it (expenseId or tripId for aggregate rules)
 * - Human-readable message
 */
public class Violation {
    private final String ruleId;
    private final String ruleName;
    private final String expenseId;  // "ALL" or tripId for aggregate rules
    private final String tripId;
    private final String message;

    public Violation(String ruleId, String ruleName, String expenseId, String tripId, String message) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.expenseId = expenseId;
        this.tripId = tripId;
        this.message = message;
    }

    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public String getExpenseId() { return expenseId; }
    public String getTripId() { return tripId; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        String scope = tripId.isEmpty() ? "expense=" + expenseId : "trip=" + tripId + ", expense=" + expenseId;
        return String.format("[%s] %s → %s", ruleName, scope, message);
    }
}
