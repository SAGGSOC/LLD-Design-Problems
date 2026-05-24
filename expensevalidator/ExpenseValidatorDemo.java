package expensevalidator;

import expensevalidator.model.Expense;
import expensevalidator.model.RuleViolation;
import expensevalidator.rule.*;
import expensevalidator.service.RuleEngine;

import java.util.Arrays;
import java.util.List;

public class ExpenseValidatorDemo {

    public static void main(String[] args) {
        // ═══════════════════════════════════════════════
        // Setup: Define Rules
        // ═══════════════════════════════════════════════
        List<Rule> rules = Arrays.asList(
            new MaxTotalAmountRule("R1", 175.0),
            new SellerTypeLimitRule("R2", "restaurant", 45.0),
            new BlockedExpenseTypeRule("R3", "Entertainment")
        );

        // ═══════════════════════════════════════════════
        // Setup: Define Expenses
        // ═══════════════════════════════════════════════
        List<Expense> expenses = Arrays.asList(
            new Expense("E1", "Item1", "Food", 250.0, "restaurant", "ABC Restaurant"),
            new Expense("E2", "Item2", "Travel", 50.0, "airline", "Delta Airlines"),
            new Expense("E3", "Item3", "Entertainment", 30.0, "online", "Netflix"),
            new Expense("E4", "Item4", "Food", 40.0, "restaurant", "XYZ Diner")
        );

        // ═══════════════════════════════════════════════
        // Evaluate
        // ═══════════════════════════════════════════════
        RuleEngine engine = new RuleEngine();

        System.out.println("═══ Expense Rule Validation ═══\n");
        System.out.println("Expenses:");
        for (Expense e : expenses) {
            System.out.println("  " + e);
        }
        System.out.println("\nRules:");
        System.out.println("  R1: Total expense should not exceed $175");
        System.out.println("  R2: Restaurant seller type should not exceed $45 per expense");
        System.out.println("  R3: Entertainment expense type is blocked");
        System.out.println();

        // Core API: evaluateRules(List<Rule>, List<Expense>)
        List<RuleViolation> violations = engine.evaluateRules(rules, expenses);

        System.out.println("─── Results ───");
        if (violations.isEmpty()) {
            System.out.println("✓ All expenses pass validation.");
        } else {
            System.out.println("✗ " + violations.size() + " violation(s) found:\n");
            for (RuleViolation v : violations) {
                System.out.println("  " + v);
            }
        }

        // ═══════════════════════════════════════════════
        // Test with clean expenses (all pass)
        // ═══════════════════════════════════════════════
        System.out.println("\n═══ Clean Expenses Test ═══\n");
        List<Expense> cleanExpenses = Arrays.asList(
            new Expense("E5", "Item5", "Food", 30.0, "restaurant", "Small Cafe"),
            new Expense("E6", "Item6", "Travel", 100.0, "airline", "United")
        );
        engine.evaluateAndPrint(rules, cleanExpenses);
    }
}
