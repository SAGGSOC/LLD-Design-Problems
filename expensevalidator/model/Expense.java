package expensevalidator.model;

/**
 * Represents a single expense entry.
 */
public class Expense {
    private final String expenseId;
    private final String itemId;
    private final String expenseType;   // e.g. "Food", "Entertainment", "Travel"
    private final double amountInUsd;
    private final String sellerType;    // e.g. "restaurant", "online", "retail"
    private final String sellerName;

    public Expense(String expenseId, String itemId, String expenseType,
                   double amountInUsd, String sellerType, String sellerName) {
        this.expenseId = expenseId;
        this.itemId = itemId;
        this.expenseType = expenseType;
        this.amountInUsd = amountInUsd;
        this.sellerType = sellerType;
        this.sellerName = sellerName;
    }

    public String getExpenseId() { return expenseId; }
    public String getItemId() { return itemId; }
    public String getExpenseType() { return expenseType; }
    public double getAmountInUsd() { return amountInUsd; }
    public String getSellerType() { return sellerType; }
    public String getSellerName() { return sellerName; }

    @Override
    public String toString() {
        return String.format("Expense{id=%s, type=%s, amount=$%.2f, seller=%s(%s)}",
            expenseId, expenseType, amountInUsd, sellerName, sellerType);
    }
}
