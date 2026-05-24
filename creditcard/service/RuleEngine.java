package creditcard.service;

import creditcard.model.Expense;
import creditcard.model.Violation;
import creditcard.rule.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core Rule Engine for the Corporate Credit Card system.
 *
 * Responsibilities:
 *   - Accept raw expense data (List<Map<String, String>>)
 *   - Convert to typed Expense objects
 *   - Run all rules and collect violations
 *
 * Design decisions:
 *   - Return type: List<Violation> — gives the caller full flexibility
 *     (approve/reject, partial approval, logging, alerting, etc.)
 *   - Rules are stateless and reusable
 *   - Engine is rule-agnostic (Open/Closed principle)
 */
public class RuleEngine {

    /**
     * Core API: evaluateRules(List<Rule>, List<Map<String, String>>) → List<Violation>
     *
     * Accepts raw expense maps (as specified in the problem).
     * Returns all violations found across all rules.
     *
     * Return type rationale:
     *   - List<Violation> is the most flexible return type
     *   - Caller can decide: reject all, reject per-expense, warn, log, etc.
     *   - Each Violation links back to the rule and expense for traceability
     *   - Empty list = all expenses approved
     */
    public List<Violation> evaluateRules(List<Rule> rules, List<Map<String, String>> rawExpenses) {
        // Convert raw maps to typed Expense objects
        List<Expense> expenses = new ArrayList<>();
        for (Map<String, String> raw : rawExpenses) {
            expenses.add(new Expense(raw));
        }

        return evaluateRulesTyped(rules, expenses);
    }

    /**
     * Typed version for internal use or when caller already has Expense objects.
     */
    public List<Violation> evaluateRulesTyped(List<Rule> rules, List<Expense> expenses) {
        List<Violation> allViolations = new ArrayList<>();

        for (Rule rule : rules) {
            List<Violation> violations = rule.evaluate(expenses);
            allViolations.addAll(violations);
        }

        return allViolations;
    }
}
