package creditcard;

import creditcard.model.Violation;
import creditcard.rule.*;
import creditcard.service.RuleEngine;

import java.util.*;

public class CreditCardRulesDemo {

    public static void main(String[] args) {
        // ═══════════════════════════════════════════════
        // Setup: Define company policy rules
        // ═══════════════════════════════════════════════
        List<Rule> rules = Arrays.asList(
            // Rule 1: No expense at a restaurant can exceed $75
            new VendorTypeLimitRule("R1", "restaurant", 75.0),

            // Rule 2: No airfare expenses
            new BanRule("R2", "expense_type", "airfare"),

            // Rule 3: No entertainment expenses
            new BanRule("R3", "expense_type", "entertainment"),

            // Rule 4: No individual expense over $250
            new MaxAmountRule("R4", 250.0),

            // Rule 5: A trip cannot exceed $2000 in total
            new TripTotalLimitRule("R5", 2000.0),

            // Rule 6: Total meal expenses cannot exceed $200 per trip
            new ExpenseTypeAggregationRule("R6", "meal", 200.0)
        );

        // ═══════════════════════════════════════════════
        // Setup: Expenses (as List<Map<String, String>> per spec)
        // ═══════════════════════════════════════════════
        List<Map<String, String>> expenses = new ArrayList<>();

        // Valid expense
        expenses.add(createExpense("001", "T1", "49.99", "client_hosting", "restaurant", "Outback Roadhouse"));

        // Violates R1: restaurant > $75
        expenses.add(createExpense("002", "T1", "120.00", "meal", "restaurant", "Fancy Steakhouse"));

        // Violates R2: airfare banned
        expenses.add(createExpense("003", "T1", "450.00", "airfare", "airline", "Delta Airlines"));

        // Violates R3: entertainment banned
        expenses.add(createExpense("004", "T1", "80.00", "entertainment", "venue", "Comedy Club"));

        // Violates R4: individual > $250 (also violates R2)
        expenses.add(createExpense("005", "T1", "1200.00", "airfare", "airline", "United Airlines"));

        // Valid meal
        expenses.add(createExpense("006", "T1", "35.00", "meal", "restaurant", "Subway"));

        // Trip T2 — within limits
        expenses.add(createExpense("007", "T2", "150.00", "lodging", "hotel", "Marriott"));
        expenses.add(createExpense("008", "T2", "60.00", "meal", "restaurant", "Chipotle"));

        // ═══════════════════════════════════════════════
        // Evaluate
        // ═══════════════════════════════════════════════
        RuleEngine engine = new RuleEngine();
        List<Violation> violations = engine.evaluateRules(rules, expenses);

        // ═══════════════════════════════════════════════
        // Output
        // ═══════════════════════════════════════════════
        System.out.println("═══ Corporate Credit Card Rules Engine ═══\n");
        System.out.println("Expenses submitted: " + expenses.size());
        System.out.println("Rules configured: " + rules.size());
        System.out.println();

        if (violations.isEmpty()) {
            System.out.println("✓ All expenses approved.");
        } else {
            System.out.println("✗ " + violations.size() + " violation(s) found:\n");
            for (Violation v : violations) {
                System.out.println("  " + v);
            }
        }

        // ═══════════════════════════════════════════════
        // Show trip-level analysis
        // ═══════════════════════════════════════════════
        System.out.println("\n─── Trip Summary ───");
        System.out.println("  Trip T1 total: $" + sumTrip(expenses, "T1"));
        System.out.println("  Trip T2 total: $" + sumTrip(expenses, "T2"));
        System.out.println("  Trip T1 meals: $" + sumTripType(expenses, "T1", "meal"));
    }

    // ─── Helpers ───

    private static Map<String, String> createExpense(String id, String tripId, String amount,
                                                      String expenseType, String vendorType, String vendorName) {
        Map<String, String> expense = new HashMap<>();
        expense.put("expense_id", id);
        expense.put("trip_id", tripId);
        expense.put("amount_usd", amount);
        expense.put("expense_type", expenseType);
        expense.put("vendor_type", vendorType);
        expense.put("vendor_name", vendorName);
        return expense;
    }

    private static double sumTrip(List<Map<String, String>> expenses, String tripId) {
        double sum = 0;
        for (Map<String, String> e : expenses) {
            if (tripId.equals(e.get("trip_id"))) {
                sum += Double.parseDouble(e.get("amount_usd"));
            }
        }
        return sum;
    }

    private static double sumTripType(List<Map<String, String>> expenses, String tripId, String type) {
        double sum = 0;
        for (Map<String, String> e : expenses) {
            if (tripId.equals(e.get("trip_id")) && type.equalsIgnoreCase(e.get("expense_type"))) {
                sum += Double.parseDouble(e.get("amount_usd"));
            }
        }
        return sum;
    }
}
