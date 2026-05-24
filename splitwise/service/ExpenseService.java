package splitwise.service;

import splitwise.enums.ExpenseType;
import splitwise.enums.SplitType;
import splitwise.model.*;
import splitwise.strategy.SplitStrategy;
import splitwise.strategy.SplitStrategyFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main entry point for creating expenses and querying balances.
 */
public class ExpenseService {
    private final Map<String, Expense> expensesById = new ConcurrentHashMap<>();
    private final BalanceSheet balanceSheet;
    private long expenseCounter = 0;

    public ExpenseService(BalanceSheet balanceSheet) {
        this.balanceSheet = balanceSheet;
    }

    /**
     * Create a non-group expense (direct between individuals).
     */
    public Expense createExpense(String description, double totalAmount,
                                 ExpenseType type, User paidBy,
                                 List<User> participants,
                                 SplitType splitType, List<Double> splitInputs) {
        return createExpense(description, totalAmount, type, paidBy, participants,
                             splitType, splitInputs, null);
    }

    /**
     * Create an expense, optionally scoped to a group.
     */
    public Expense createExpense(String description, double totalAmount,
                                 ExpenseType type, User paidBy,
                                 List<User> participants,
                                 SplitType splitType, List<Double> splitInputs,
                                 String groupId) {
        if (totalAmount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("Participants required");
        }

        // Compute splits via strategy
        SplitStrategy strategy = SplitStrategyFactory.getStrategy(splitType);
        List<Split> splits = strategy.computeSplits(totalAmount, participants, splitInputs);

        String expenseId = "EXP-" + (++expenseCounter);
        Expense expense = new Expense(
            expenseId, description, totalAmount, type,
            paidBy, splitType, splits, groupId
        );

        expensesById.put(expenseId, expense);
        balanceSheet.applyExpense(expense);
        return expense;
    }

    public Expense getExpense(String expenseId) {
        return expensesById.get(expenseId);
    }

    public List<Expense> getUserExpenses(String userId) {
        List<Expense> result = new ArrayList<>();
        for (Expense expense : expensesById.values()) {
            if (expense.getPaidBy().getUserId().equals(userId)) {
                result.add(expense);
                continue;
            }
            for (Split split : expense.getSplits()) {
                if (split.getUser().getUserId().equals(userId)) {
                    result.add(expense);
                    break;
                }
            }
        }
        return result;
    }

    public List<Expense> getGroupExpenses(String groupId) {
        List<Expense> result = new ArrayList<>();
        for (Expense expense : expensesById.values()) {
            if (groupId.equals(expense.getGroupId())) {
                result.add(expense);
            }
        }
        return result;
    }
}
