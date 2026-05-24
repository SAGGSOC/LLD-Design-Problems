package splitwise.model;

/**
 * A suggested settlement: "from" pays "to" "amount" to zero out their balances.
 */
public class Transaction {
    private final User from;
    private final User to;
    private final double amount;

    public Transaction(User from, User to, double amount) {
        this.from = from;
        this.to = to;
        this.amount = amount;
    }

    public User getFrom()   { return from; }
    public User getTo()     { return to; }
    public double getAmount() { return amount; }

    @Override
    public String toString() {
        return String.format("%s pays %s $%.2f",
            from.getName(), to.getName(), amount);
    }
}
