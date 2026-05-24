package splitwise.service;

import splitwise.model.Transaction;
import splitwise.model.User;

import java.util.*;

/**
 * Computes the minimum set of transactions needed to settle all balances.
 *
 * Algorithm (greedy, near-optimal — exact optimal is NP-hard):
 *   1. Compute net balance for each user (what they owe / are owed overall).
 *   2. Separate into creditors (net > 0) and debtors (net < 0).
 *   3. Repeatedly: match largest debtor to largest creditor, settle min of the two.
 *   4. Remove settled parties, repeat until empty.
 *
 * This produces at most N-1 transactions for N people, often fewer.
 * It's greedy but works well in practice.
 */
public class SettlementService {

    private static final double EPSILON = 0.01;

    /**
     * @param usersInScope the users to settle among (e.g., group members, or all users)
     */
    public List<Transaction> settleAll(BalanceSheet balanceSheet, Collection<User> usersInScope) {
        // Step 1: compute net balance per user
        Map<User, Double> netBalances = new HashMap<>();
        for (User user : usersInScope) {
            double net = balanceSheet.getNetBalance(user.getUserId());
            if (Math.abs(net) > EPSILON) {
                netBalances.put(user, net);
            }
        }

        // Step 2: separate into creditors and debtors, sorted by magnitude
        PriorityQueue<UserBalance> creditors = new PriorityQueue<>(
            Comparator.comparingDouble((UserBalance ub) -> ub.amount).reversed());
        PriorityQueue<UserBalance> debtors = new PriorityQueue<>(
            Comparator.comparingDouble((UserBalance ub) -> ub.amount));  // most negative first

        for (Map.Entry<User, Double> entry : netBalances.entrySet()) {
            if (entry.getValue() > 0) {
                creditors.offer(new UserBalance(entry.getKey(), entry.getValue()));
            } else {
                debtors.offer(new UserBalance(entry.getKey(), entry.getValue()));
            }
        }

        // Step 3: greedily match largest debtor to largest creditor
        List<Transaction> transactions = new ArrayList<>();

        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            UserBalance creditor = creditors.poll();
            UserBalance debtor = debtors.poll();

            double settleAmount = Math.min(creditor.amount, -debtor.amount);
            // Round to cents to avoid floating-point drift
            settleAmount = Math.round(settleAmount * 100) / 100.0;

            transactions.add(new Transaction(debtor.user, creditor.user, settleAmount));

            double newCreditorBalance = creditor.amount - settleAmount;
            double newDebtorBalance = debtor.amount + settleAmount;

            if (newCreditorBalance > EPSILON) {
                creditors.offer(new UserBalance(creditor.user, newCreditorBalance));
            }
            if (newDebtorBalance < -EPSILON) {
                debtors.offer(new UserBalance(debtor.user, newDebtorBalance));
            }
        }

        return transactions;
    }

    /** Settle just one pair — returns what A should pay B (or empty if balanced). */
    public Optional<Transaction> settlePair(BalanceSheet balanceSheet, User a, User b) {
        double aOwesB = balanceSheet.getBalance(a.getUserId(), b.getUserId());
        if (Math.abs(aOwesB) < EPSILON) return Optional.empty();

        if (aOwesB > 0) {
            return Optional.of(new Transaction(a, b, Math.round(aOwesB * 100) / 100.0));
        } else {
            return Optional.of(new Transaction(b, a, Math.round(-aOwesB * 100) / 100.0));
        }
    }

    private static class UserBalance {
        final User user;
        final double amount;
        UserBalance(User user, double amount) {
            this.user = user;
            this.amount = amount;
        }
    }
}
