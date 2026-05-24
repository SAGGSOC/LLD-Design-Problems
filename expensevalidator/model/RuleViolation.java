package expensevalidator.model;

/**
 * Represents a single rule violation flagged against an expense.
 */
public class RuleViolation {
    private final String ruleId;
    private final String ruleName;
    private final String expenseId;
    private final String message;

    public RuleViolation(String ruleId, String ruleName, String expenseId, String message) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.expenseId = expenseId;
        this.message = message;
    }

    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public String getExpenseId() { return expenseId; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return String.format("[VIOLATION] Rule: %s | Expense: %s | %s", ruleName, expenseId, message);
    }
}
