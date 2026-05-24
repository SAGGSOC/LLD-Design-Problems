package splitwise.model;

/**
 * Represents one participant's share of an expense.
 * amount = how much this user owes for the expense.
 */
public class Split {
    private final User user;
    private final double amount;

    public Split(User user, double amount) {
        this.user = user;
        this.amount = amount;
    }

    public User getUser()   { return user; }
    public double getAmount() { return amount; }

    @Override
    public String toString() {
        return user.getName() + " owes $" + String.format("%.2f", amount);
    }
}
