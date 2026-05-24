package expensevalidator.service;

import expensevalidator.model.Expense;
import expensevalidator.model.RuleViolation;
import expensevalidator.rule.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Engine — evaluates a list of rules against a list of expenses.
 *
 * Design:
 * - Strategy Pattern: Each Rule is a strategy with its own evaluate() logic.
 * - Open/Closed Principle: New rules can be added without modifying this engine.
 * - Single Responsibility: Engine only orchestrates; rules own their logic.
 */
public class RuleEngine {

    /**
     * Evaluate all rules against the given expenses.
     * Returns all violations found.
     *
     * Time: O(R * N) where R = number of rules, N = number of expenses
     */
    public List<RuleViolation> evaluateRules(List<Rule> rules, List<Expense> expenses) {
        List<RuleViolation> allViolations = new ArrayList<>();

        for (Rule rule : rules) {
            List<RuleViolation> violations = rule.evaluate(expenses);
            allViolations.addAll(violations);
        }

        return allViolations;
    }

    /**
     * Evaluate and print results.
     */
    public void evaluateAndPrint(List<Rule> rules, List<Expense> expenses) {
        List<RuleViolation> violations = evaluateRules(rules, expenses);

        if (violations.isEmpty()) {
            System.out.println("✓ All expenses pass validation.");
        } else {
            System.out.println("✗ " + violations.size() + " violation(s) found:");
            for (RuleViolation v : violations) {
                System.out.println("  " + v);
            }
        }
    }
}
