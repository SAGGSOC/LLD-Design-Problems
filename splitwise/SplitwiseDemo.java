package splitwise;

import splitwise.enums.ExpenseType;
import splitwise.enums.SplitType;
import splitwise.model.Expense;
import splitwise.model.Group;
import splitwise.model.Transaction;
import splitwise.model.User;
import splitwise.service.*;

import java.util.Arrays;
import java.util.List;

public class SplitwiseDemo {

    public static void main(String[] args) {
        UserService userService = new UserService();
        GroupService groupService = new GroupService();
        BalanceSheet balanceSheet = new BalanceSheet();
        ExpenseService expenseService = new ExpenseService(balanceSheet);
        SettlementService settlementService = new SettlementService();

        // ─── Create users ───
        User alice   = userService.createUser("Alice",   "[email]",   "555-0001");
        User bob     = userService.createUser("Bob",     "[email]",     "555-0002");
        User charlie = userService.createUser("Charlie", "[email]", "555-0003");
        User dave    = userService.createUser("Dave",    "[email]",    "555-0004");

        // ─── Create a group ───
        Group trip = groupService.createGroup("Weekend Trip", alice);
        groupService.addMember(trip.getGroupId(), bob);
        groupService.addMember(trip.getGroupId(), charlie);
        groupService.addMember(trip.getGroupId(), dave);

        System.out.println("=== " + trip.getName() + " ===");
        System.out.println("Members: " + trip.getMembers());
        System.out.println();

        // ─── Expense 1: Alice pays $120 for dinner, split equally 4 ways ───
        System.out.println("--- Expense 1: Alice pays $120 for dinner (equal 4-way) ---");
        Expense e1 = expenseService.createExpense(
            "Dinner at Italian place", 120.00, ExpenseType.FOOD,
            alice, Arrays.asList(alice, bob, charlie, dave),
            SplitType.EQUAL, null, trip.getGroupId());
        trip.addExpense(e1.getExpenseId());
        e1.getSplits().forEach(s -> System.out.println("  " + s));

        // ─── Expense 2: Bob pays $80 for gas, split among Bob, Charlie, Dave only ───
        System.out.println("\n--- Expense 2: Bob pays $80 for gas (equal 3-way, Alice not included) ---");
        Expense e2 = expenseService.createExpense(
            "Gas for road trip", 80.00, ExpenseType.TRAVEL,
            bob, Arrays.asList(bob, charlie, dave),
            SplitType.EQUAL, null, trip.getGroupId());
        trip.addExpense(e2.getExpenseId());
        e2.getSplits().forEach(s -> System.out.println("  " + s));

        // ─── Expense 3: Charlie pays $200 for hotel, exact split ───
        System.out.println("\n--- Expense 3: Charlie pays $200 for hotel (EXACT split) ---");
        // Alice: $60, Bob: $50, Charlie: $50, Dave: $40
        Expense e3 = expenseService.createExpense(
            "Hotel night", 200.00, ExpenseType.TRAVEL,
            charlie, Arrays.asList(alice, bob, charlie, dave),
            SplitType.EXACT, Arrays.asList(60.0, 50.0, 50.0, 40.0),
            trip.getGroupId());
        trip.addExpense(e3.getExpenseId());
        e3.getSplits().forEach(s -> System.out.println("  " + s));

        // ─── Expense 4: Dave pays $100 for groceries, percentage split ───
        System.out.println("\n--- Expense 4: Dave pays $100 for groceries (30/30/20/20 percent) ---");
        Expense e4 = expenseService.createExpense(
            "Groceries", 100.00, ExpenseType.FOOD,
            dave, Arrays.asList(alice, bob, charlie, dave),
            SplitType.PERCENTAGE, Arrays.asList(30.0, 30.0, 20.0, 20.0),
            trip.getGroupId());
        trip.addExpense(e4.getExpenseId());
        e4.getSplits().forEach(s -> System.out.println("  " + s));

        // ─── Show balances ───
        System.out.println("\n=== Individual Net Balances ===");
        for (User user : Arrays.asList(alice, bob, charlie, dave)) {
            double net = balanceSheet.getNetBalance(user.getUserId());
            String status;
            if (net > 0.01)      status = "is owed $" + String.format("%.2f", net);
            else if (net < -0.01) status = "owes $" + String.format("%.2f", -net);
            else                  status = "is settled up";
            System.out.printf("  %-10s %s%n", user.getName(), status);
        }

        // ─── Pairwise balances ───
        System.out.println("\n=== Pairwise Balances (non-zero) ===");
        User[] allUsers = {alice, bob, charlie, dave};
        for (int i = 0; i < allUsers.length; i++) {
            for (int j = i + 1; j < allUsers.length; j++) {
                double balance = balanceSheet.getBalance(
                    allUsers[i].getUserId(), allUsers[j].getUserId());
                if (Math.abs(balance) > 0.01) {
                    if (balance > 0) {
                        System.out.printf("  %s owes %s $%.2f%n",
                            allUsers[i].getName(), allUsers[j].getName(), balance);
                    } else {
                        System.out.printf("  %s owes %s $%.2f%n",
                            allUsers[j].getName(), allUsers[i].getName(), -balance);
                    }
                }
            }
        }

        // ─── Settle up: minimum transactions ───
        System.out.println("\n=== Minimum Settlement Transactions ===");
        List<Transaction> transactions = settlementService.settleAll(
            balanceSheet, Arrays.asList(alice, bob, charlie, dave));
        for (Transaction t : transactions) {
            System.out.println("  " + t);
        }
        System.out.println("Total transactions: " + transactions.size()
            + " (vs. up to " + (allUsers.length * (allUsers.length - 1) / 2) + " pairwise)");

        // ─── Apply settlements ───
        System.out.println("\n=== After settling up ===");
        for (Transaction t : transactions) {
            balanceSheet.settleUp(t.getFrom(), t.getTo(), t.getAmount());
        }
        for (User user : Arrays.asList(alice, bob, charlie, dave)) {
            double net = balanceSheet.getNetBalance(user.getUserId());
            System.out.printf("  %-10s net balance: $%.2f%n", user.getName(), net);
        }
    }
}
