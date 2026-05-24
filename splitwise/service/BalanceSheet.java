package splitwise.service;

import splitwise.model.Expense;
import splitwise.model.Split;
import splitwise.model.User;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pairwise balances between users.
 *
 * balances[A][B] = amount A owes B
 *   positive → A owes B
 *   negative → B owes A (we keep it consistent by always updating both directions)
 *
 * Example: Alice pays $90 for dinner, split equally 3 ways between Alice, Bob, Charlie.
 *   → Bob owes Alice $30    → balances[bob][alice] = 30, balances[alice][bob] = -30
 *   → Charlie owes Alice $30 → balances[charlie][alice] = 30, balances[alice][charlie] = -30
 */
public class BalanceSheet {

    // userId → (otherUserId → amount owed)
    private final Map<String, Map<String, Double>> balances = new ConcurrentHashMap<>();

    /**
     * Apply an expense to the balance sheet.
     * For each split, the split.user owes the expense.paidBy the split.amount
     * (minus what the paidBy themselves owes — if they're a participant).
     */
    public synchronized void applyExpense(Expense expense) {
        User paidBy = expense.getPaidBy();

        for (Split split : expense.getSplits()) {
            User participant = split.getUser();
            double amount = split.getAmount();

            if (participant.equals(paidBy)) {
                continue;  // payer doesn't owe themselves
            }

            // participant owes paidBy $amount
            addBalance(participant.getUserId(), paidBy.getUserId(), amount);
            addBalance(paidBy.getUserId(), participant.getUserId(), -amount);
        }
    }

    /**
     * Record a direct settlement: "from" paid "to" some amount.
     * Reduces what "from" owes "to" by that amount.
     */
    public synchronized void settleUp(User from, User to, double amount) {
        addBalance(from.getUserId(), to.getUserId(), -amount);
        addBalance(to.getUserId(), from.getUserId(), amount);
    }

    /** Get what user A owes user B (can be negative if B owes A). */
    public double getBalance(String userIdA, String userIdB) {
        return balances.getOrDefault(userIdA, new HashMap<>())
                       .getOrDefault(userIdB, 0.0);
    }

    /**
     * Net balance for a user:
     *   positive → they are owed money overall
     *   negative → they owe money overall
     *   zero     → settled up
     */
    public synchronized double getNetBalance(String userId) {
        // net = sum of (what others owe me) - (what I owe others)
        // Since we store both directions consistently, sum of -balances[userId][*] works:
        //   balances[userId][X] = what userId owes X
        //   net = -sum(balances[userId][*])
        Map<String, Double> myBalances = balances.getOrDefault(userId, new HashMap<>());
        return -myBalances.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public synchronized Map<String, Double> getAllBalancesFor(String userId) {
        return new HashMap<>(balances.getOrDefault(userId, new HashMap<>()));
    }

    private void addBalance(String from, String to, double amount) {
        balances.computeIfAbsent(from, k -> new HashMap<>())
                .merge(to, amount, Double::sum);
    }
}
