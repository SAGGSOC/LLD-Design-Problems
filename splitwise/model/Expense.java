package splitwise.model;

import splitwise.enums.ExpenseType;
import splitwise.enums.SplitType;

import java.time.Instant;
import java.util.List;

public class Expense {
    private final String expenseId;
    private final String description;
    private final double totalAmount;
    private final ExpenseType type;
    private final User paidBy;
    private final SplitType splitType;
    private final List<Split> splits;
    private final String groupId;    // null for non-group expenses
    private final Instant createdAt;

    public Expense(String expenseId, String description, double totalAmount,
                   ExpenseType type, User paidBy, SplitType splitType,
                   List<Split> splits, String groupId) {
        this.expenseId = expenseId;
        this.description = description;
        this.totalAmount = totalAmount;
        this.type = type;
        this.paidBy = paidBy;
        this.splitType = splitType;
        this.splits = splits;
        this.groupId = groupId;
        this.createdAt = Instant.now();
    }

    public String getExpenseId()       { return expenseId; }
    public String getDescription()     { return description; }
    public double getTotalAmount()     { return totalAmount; }
    public ExpenseType getType()       { return type; }
    public User getPaidBy()            { return paidBy; }
    public SplitType getSplitType()    { return splitType; }
    public List<Split> getSplits()     { return splits; }
    public String getGroupId()         { return groupId; }
    public Instant getCreatedAt()      { return createdAt; }
}
