import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Comparator;

// ═══════════════════════════════════════════════
// Strategy Pattern: Split Validation & Calculation
// ═══════════════════════════════════════════════

interface ExpenseSplit {
    void validateSplitRequest(List<Split> splitList, double totalAmount);
}

class EqualExpenseSplit implements ExpenseSplit {

    @Override
    public void validateSplitRequest(List<Split> splitList, double totalAmount) {
        if (splitList.isEmpty()) throw new IllegalArgumentException("Split list cannot be empty");

        double amountShouldBePresent = Math.round(totalAmount / splitList.size() * 100.0) / 100.0;

        // Auto-fix: set equal amounts if not already set
        for (Split split : splitList) {
            if (split.getAmountOwe() == 0) {
                split.setAmountOwe(amountShouldBePresent);
            } else if (Math.abs(split.getAmountOwe() - amountShouldBePresent) > 0.01) {
                throw new IllegalArgumentException(
                    "Equal split requires each share to be " + amountShouldBePresent +
                    ", but got " + split.getAmountOwe());
            }
        }
    }
}

class PercentageExpenseSplit implements ExpenseSplit {

    @Override
    public void validateSplitRequest(List<Split> splitList, double totalAmount) {
        if (splitList.isEmpty()) throw new IllegalArgumentException("Split list cannot be empty");

        // For percentage split, amountOwe stores the percentage
        double totalPercent = 0;
        for (Split split : splitList) {
            if (split.getAmountOwe() < 0) {
                throw new IllegalArgumentException("Percentage cannot be negative");
            }
            totalPercent += split.getAmountOwe();
        }

        if (Math.abs(totalPercent - 100.0) > 0.01) {
            throw new IllegalArgumentException("Percentages must sum to 100, got: " + totalPercent);
        }

        // Convert percentages to actual amounts
        for (Split split : splitList) {
            double actualAmount = totalAmount * split.getAmountOwe() / 100.0;
            split.setAmountOwe(Math.round(actualAmount * 100.0) / 100.0);
        }
    }
}

class UnequalExpenseSplit implements ExpenseSplit {

    @Override
    public void validateSplitRequest(List<Split> splitList, double totalAmount) {
        if (splitList.isEmpty()) throw new IllegalArgumentException("Split list cannot be empty");

        double sum = 0;
        for (Split split : splitList) {
            if (split.getAmountOwe() < 0) {
                throw new IllegalArgumentException("Split amount cannot be negative");
            }
            sum += split.getAmountOwe();
        }

        if (Math.abs(sum - totalAmount) > 0.01) {
            throw new IllegalArgumentException(
                "Unequal split amounts sum to " + sum + " but total is " + totalAmount);
        }
    }
}

// ═══════════════════════════════════════════════
// Factory
// ═══════════════════════════════════════════════

enum ExpenseSplitType {
    EQUAL,
    UNEQUAL,
    PERCENTAGE
}

class SplitFactory {

    public static ExpenseSplit getSplitObject(ExpenseSplitType splitType) {
        switch (splitType) {
            case EQUAL:
                return new EqualExpenseSplit();
            case UNEQUAL:
                return new UnequalExpenseSplit();
            case PERCENTAGE:
                return new PercentageExpenseSplit();
            default:
                throw new IllegalArgumentException("Unknown split type: " + splitType);
        }
    }
}

// ═══════════════════════════════════════════════
// Models
// ═══════════════════════════════════════════════

class Split {

    User user;
    double amountOwe;

    public Split(User user, double amountOwe) {
        this.user = user;
        this.amountOwe = amountOwe;
    }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public double getAmountOwe() { return amountOwe; }
    public void setAmountOwe(double amountOwe) { this.amountOwe = amountOwe; }
}

class Expense {
    String expenseId;
    String description;
    double expenseAmount;
    User paidByUser;
    ExpenseSplitType splitType;
    List<Split> splitDetails = new ArrayList<>();

    public Expense(String expenseId, double expenseAmount, String description,
                   User paidByUser, ExpenseSplitType splitType, List<Split> splitDetails) {
        this.expenseId = expenseId;
        this.expenseAmount = expenseAmount;
        this.description = description;
        this.paidByUser = paidByUser;
        this.splitType = splitType;
        this.splitDetails.addAll(splitDetails);
    }
}

class User {

    String userId;
    String userName;
    UserExpenseBalanceSheet userExpenseBalanceSheet;

    public User(String id, String userName) {
        this.userId = id;
        this.userName = userName;
        userExpenseBalanceSheet = new UserExpenseBalanceSheet();
    }

    public String getUserId() { return userId; }
    public String getUserName() { return userName; }
    public UserExpenseBalanceSheet getUserExpenseBalanceSheet() { return userExpenseBalanceSheet; }
}

class Balance {

    double amountOwe;
    double amountGetBack;

    public double getAmountOwe() { return amountOwe; }
    public void setAmountOwe(double amountOwe) { this.amountOwe = amountOwe; }
    public double getAmountGetBack() { return amountGetBack; }
    public void setAmountGetBack(double amountGetBack) { this.amountGetBack = amountGetBack; }
}

class UserExpenseBalanceSheet {

    Map<String, Balance> userVsBalance;
    double totalYourExpense;
    double totalPayment;
    double totalYouOwe;
    double totalYouGetBack;

    public UserExpenseBalanceSheet() {
        userVsBalance = new HashMap<>();
        totalYourExpense = 0;
        totalYouOwe = 0;
        totalYouGetBack = 0;
    }

    public Map<String, Balance> getUserVsBalance() { return userVsBalance; }
    public double getTotalYourExpense() { return totalYourExpense; }
    public void setTotalYourExpense(double totalYourExpense) { this.totalYourExpense = totalYourExpense; }
    public double getTotalYouOwe() { return totalYouOwe; }
    public void setTotalYouOwe(double totalYouOwe) { this.totalYouOwe = totalYouOwe; }
    public double getTotalYouGetBack() { return totalYouGetBack; }
    public void setTotalYouGetBack(double totalYouGetBack) { this.totalYouGetBack = totalYouGetBack; }
    public double getTotalPayment() { return totalPayment; }
    public void setTotalPayment(double totalPayment) { this.totalPayment = totalPayment; }
}

// ═══════════════════════════════════════════════
// Settlement Transaction
// ═══════════════════════════════════════════════

class Transaction {
    private final User from;
    private final User to;
    private final double amount;

    public Transaction(User from, User to, double amount) {
        this.from = from;
        this.to = to;
        this.amount = amount;
    }

    public User getFrom() { return from; }
    public User getTo() { return to; }
    public double getAmount() { return amount; }

    @Override
    public String toString() {
        return from.getUserName() + " pays " + to.getUserName() + " $" + String.format("%.2f", amount);
    }
}

// ═══════════════════════════════════════════════
// Strategy Pattern: Settlement Algorithms
// ═══════════════════════════════════════════════

interface SettlementStrategy {
    List<Transaction> settle(List<User> users);
}

/**
 * Greedy Settlement: Minimize number of transactions.
 *
 * Algorithm:
 *   1. Compute net balance for each user (getBack - owe)
 *   2. Separate into debtors (net < 0) and creditors (net > 0)
 *   3. Sort both by amount (largest first)
 *   4. Match largest debtor with largest creditor
 *   5. Transfer min(debt, credit), reduce both, repeat
 *
 * Guarantees at most N-1 transactions (vs N*(N-1)/2 pairwise)
 */
class GreedySettlementStrategy implements SettlementStrategy {

    @Override
    public List<Transaction> settle(List<User> users) {
        // Calculate net balance for each user
        List<double[]> debtors = new ArrayList<>();   // [index, amount they owe]
        List<double[]> creditors = new ArrayList<>(); // [index, amount they are owed]

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            UserExpenseBalanceSheet sheet = user.getUserExpenseBalanceSheet();
            double net = sheet.getTotalYouGetBack() - sheet.getTotalYouOwe();

            if (net < -0.01) {
                debtors.add(new double[]{i, -net}); // positive amount they owe
            } else if (net > 0.01) {
                creditors.add(new double[]{i, net}); // positive amount owed to them
            }
        }

        // Sort largest first for greedy matching
        debtors.sort((a, b) -> Double.compare(b[1], a[1]));
        creditors.sort((a, b) -> Double.compare(b[1], a[1]));

        List<Transaction> transactions = new ArrayList<>();
        int i = 0, j = 0;

        while (i < debtors.size() && j < creditors.size()) {
            double debt = debtors.get(i)[1];
            double credit = creditors.get(j)[1];
            double transfer = Math.min(debt, credit);

            User from = users.get((int) debtors.get(i)[0]);
            User to = users.get((int) creditors.get(j)[0]);

            transfer = Math.round(transfer * 100.0) / 100.0;
            if (transfer > 0.01) {
                transactions.add(new Transaction(from, to, transfer));
            }

            debtors.get(i)[1] -= transfer;
            creditors.get(j)[1] -= transfer;

            if (debtors.get(i)[1] < 0.01) i++;
            if (creditors.get(j)[1] < 0.01) j++;
        }

        return transactions;
    }
}

/**
 * Pairwise Settlement: Settle each pair individually.
 *
 * Simpler but generates more transactions.
 * For each pair (A, B), if A owes B net > 0, create one transaction.
 * Can result in up to N*(N-1)/2 transactions.
 */
class PairwiseSettlementStrategy implements SettlementStrategy {

    @Override
    public List<Transaction> settle(List<User> users) {
        List<Transaction> transactions = new ArrayList<>();

        for (int i = 0; i < users.size(); i++) {
            for (int j = i + 1; j < users.size(); j++) {
                User userA = users.get(i);
                User userB = users.get(j);

                UserExpenseBalanceSheet sheetA = userA.getUserExpenseBalanceSheet();
                Balance balanceWithB = sheetA.getUserVsBalance().get(userB.getUserId());

                if (balanceWithB != null) {
                    double netAOwesB = balanceWithB.getAmountOwe() - balanceWithB.getAmountGetBack();

                    if (netAOwesB > 0.01) {
                        transactions.add(new Transaction(userA, userB, Math.round(netAOwesB * 100.0) / 100.0));
                    } else if (netAOwesB < -0.01) {
                        transactions.add(new Transaction(userB, userA, Math.round(-netAOwesB * 100.0) / 100.0));
                    }
                }
            }
        }

        return transactions;
    }
}

// ═══════════════════════════════════════════════
// Controllers
// ═══════════════════════════════════════════════

class ExpenseController {

    BalanceSheetController balanceSheetController;

    public ExpenseController() {
        balanceSheetController = new BalanceSheetController();
    }

    public Expense createExpense(String expenseId, String description, double expenseAmount,
                                 List<Split> splitDetails, ExpenseSplitType splitType, User paidByUser) {

        ExpenseSplit expenseSplit = SplitFactory.getSplitObject(splitType);
        expenseSplit.validateSplitRequest(splitDetails, expenseAmount);

        Expense expense = new Expense(expenseId, expenseAmount, description, paidByUser, splitType, splitDetails);

        balanceSheetController.updateUserExpenseBalanceSheet(paidByUser, splitDetails, expenseAmount);

        return expense;
    }
}

class Group {

    String groupId;
    String groupName;
    List<User> groupMembers;
    List<Expense> expenseList;
    ExpenseController expenseController;

    Group() {
        groupMembers = new ArrayList<>();
        expenseList = new ArrayList<>();
        expenseController = new ExpenseController();
    }

    public void addMember(User member) { groupMembers.add(member); }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public Expense createExpense(String expenseId, String description, double expenseAmount,
                                 List<Split> splitDetails, ExpenseSplitType splitType, User paidByUser) {
        Expense expense = expenseController.createExpense(expenseId, description, expenseAmount, splitDetails, splitType, paidByUser);
        expenseList.add(expense);
        return expense;
    }
}

class GroupController {

    List<Group> groupList;

    public GroupController() {
        groupList = new ArrayList<>();
    }

    public void createNewGroup(String groupId, String groupName, User createdByUser) {
        Group group = new Group();
        group.setGroupId(groupId);
        group.setGroupName(groupName);
        group.addMember(createdByUser);
        groupList.add(group);
    }

    public Group getGroup(String groupId) {
        for (Group group : groupList) {
            if (group.getGroupId().equals(groupId)) return group;
        }
        return null;
    }
}

class UserController {

    List<User> userList;

    public UserController() {
        userList = new ArrayList<>();
    }

    public void addUser(User user) { userList.add(user); }

    public User getUser(String userID) {
        for (User user : userList) {
            if (user.getUserId().equals(userID)) return user;
        }
        return null;
    }

    public List<User> getAllUsers() { return userList; }
}

class BalanceSheetController {

    /**
     * Updates balance sheets of BOTH the payer and each debtor when an expense is created.
     * Double-entry bookkeeping: every debt appears in both users' books from opposite perspectives.
     *
     * Example: Alice pays $900, split equally among Alice($300), Bob($300), Charlie($300)
     *
     * For each split:
     *   Case A (split.user == payer): Just track payer's own consumption. No debt.
     *   Case B (split.user != payer): Create debt on both sides:
     *     - PAYER side:  "X owes me $amount"   → totalYouGetBack += amount, userVsBalance[X].getBack += amount
     *     - DEBTOR side: "I owe payer $amount"  → totalYouOwe += amount, userVsBalance[payer].owe += amount
     *
     * After processing:
     *   Alice: totalPayment=900, totalYourExpense=300, totalGetBack=600, totalOwe=0
     *   Bob:   totalPayment=0,   totalYourExpense=300, totalGetBack=0,   totalOwe=300
     *
     * Invariant: sum of all users' (totalGetBack - totalOwe) == 0 (books always balance)
     */
    public void updateUserExpenseBalanceSheet(User expensePaidBy, List<Split> splits, double totalExpenseAmount) {

        // Get payer's balance sheet
        UserExpenseBalanceSheet paidByUserExpenseSheet = expensePaidBy.getUserExpenseBalanceSheet();

        // Track total money that left payer's wallet
        paidByUserExpenseSheet.setTotalPayment(paidByUserExpenseSheet.getTotalPayment() + totalExpenseAmount);

        // Process each person's share
        for (Split split : splits) {

            User userOwe = split.getUser();           // Who is this split for?
            UserExpenseBalanceSheet oweUserExpenseSheet = userOwe.getUserExpenseBalanceSheet();
            double oweAmount = split.getAmountOwe();  // Their share amount

            if (expensePaidBy.getUserId().equals(userOwe.getUserId())) {
                // ──── CASE A: Payer's own share ────
                // Just record what payer consumed. No debt to self.
                paidByUserExpenseSheet.setTotalYourExpense(paidByUserExpenseSheet.getTotalYourExpense() + oweAmount);
            } else {
                // ──── CASE B: Someone else owes the payer ────

                // ── PAYER SIDE: "I am owed $oweAmount more" ──
                paidByUserExpenseSheet.setTotalYouGetBack(paidByUserExpenseSheet.getTotalYouGetBack() + oweAmount);

                // Get or create pairwise balance: payer → debtor
                Balance userOweBalance;
                if (paidByUserExpenseSheet.getUserVsBalance().containsKey(userOwe.getUserId())) {
                    userOweBalance = paidByUserExpenseSheet.getUserVsBalance().get(userOwe.getUserId());
                } else {
                    userOweBalance = new Balance();
                    paidByUserExpenseSheet.getUserVsBalance().put(userOwe.getUserId(), userOweBalance);
                }
                // In payer's books: "debtor owes me $oweAmount"
                userOweBalance.setAmountGetBack(userOweBalance.getAmountGetBack() + oweAmount);

                // ── DEBTOR SIDE: "I owe payer $oweAmount more" ──
                oweUserExpenseSheet.setTotalYouOwe(oweUserExpenseSheet.getTotalYouOwe() + oweAmount);
                // Debtor consumed $oweAmount worth (even though they didn't pay)
                oweUserExpenseSheet.setTotalYourExpense(oweUserExpenseSheet.getTotalYourExpense() + oweAmount);

                // Get or create pairwise balance: debtor → payer
                Balance userPaidBalance;
                if (oweUserExpenseSheet.getUserVsBalance().containsKey(expensePaidBy.getUserId())) {
                    userPaidBalance = oweUserExpenseSheet.getUserVsBalance().get(expensePaidBy.getUserId());
                } else {
                    userPaidBalance = new Balance();
                    oweUserExpenseSheet.getUserVsBalance().put(expensePaidBy.getUserId(), userPaidBalance);
                }
                // In debtor's books: "I owe payer $oweAmount"
                userPaidBalance.setAmountOwe(userPaidBalance.getAmountOwe() + oweAmount);
            }
        }
    }

    public void showBalanceSheetOfUser(User user) {
        System.out.println("---------------------------------------");
        System.out.println("Balance sheet of user : " + user.getUserId() + " (" + user.getUserName() + ")");

        UserExpenseBalanceSheet sheet = user.getUserExpenseBalanceSheet();
        System.out.println("TotalYourExpense: " + sheet.getTotalYourExpense());
        System.out.println("TotalGetBack: " + sheet.getTotalYouGetBack());
        System.out.println("TotalYourOwe: " + sheet.getTotalYouOwe());
        System.out.println("TotalPaymentMade: " + sheet.getTotalPayment());

        for (Map.Entry<String, Balance> entry : sheet.getUserVsBalance().entrySet()) {
            String userID = entry.getKey();
            Balance balance = entry.getValue();
            System.out.println("  userID:" + userID + " YouGetBack:" + balance.getAmountGetBack() + " YouOwe:" + balance.getAmountOwe());
        }
        System.out.println("---------------------------------------");
    }
}

// ═══════════════════════════════════════════════
// Main
// ═══════════════════════════════════════════════

public class Splitwise {

    UserController userController;
    GroupController groupController;
    BalanceSheetController balanceSheetController;

    Splitwise() {
        userController = new UserController();
        groupController = new GroupController();
        balanceSheetController = new BalanceSheetController();
    }

    public void demo() {

        setupUserAndGroup();

        //Step1: add members to the group
        Group group = groupController.getGroup("G1001");
        group.addMember(userController.getUser("U2001"));
        group.addMember(userController.getUser("U3001"));
        group.addMember(userController.getUser("U4001"));

        //Step2: EQUAL split — User1 pays $900 for breakfast, split 3 ways
        System.out.println("═══ Expense 1: EQUAL Split ═══");
        System.out.println("User1 pays $900 for Breakfast, split among U1, U2, U3\n");
        List<Split> splits = new ArrayList<>();
        splits.add(new Split(userController.getUser("U1001"), 300));
        splits.add(new Split(userController.getUser("U2001"), 300));
        splits.add(new Split(userController.getUser("U3001"), 300));
        group.createExpense("Exp1001", "Breakfast", 900, splits, ExpenseSplitType.EQUAL, userController.getUser("U1001"));

        //Step3: UNEQUAL split — User2 pays $500 for lunch
        System.out.println("═══ Expense 2: UNEQUAL Split ═══");
        System.out.println("User2 pays $500 for Lunch, U1 owes 400, U2 owes 100\n");
        List<Split> splits2 = new ArrayList<>();
        splits2.add(new Split(userController.getUser("U1001"), 400));
        splits2.add(new Split(userController.getUser("U2001"), 100));
        group.createExpense("Exp1002", "Lunch", 500, splits2, ExpenseSplitType.UNEQUAL, userController.getUser("U2001"));

        //Step4: PERCENTAGE split — User3 pays $1000, split 40/30/20/10
        System.out.println("═══ Expense 3: PERCENTAGE Split ═══");
        System.out.println("User3 pays $1000 for Hotel, split 40%/30%/20%/10%\n");
        List<Split> splits3 = new ArrayList<>();
        splits3.add(new Split(userController.getUser("U1001"), 40));  // 40%
        splits3.add(new Split(userController.getUser("U2001"), 30));  // 30%
        splits3.add(new Split(userController.getUser("U3001"), 20));  // 20%
        splits3.add(new Split(userController.getUser("U4001"), 10));  // 10%
        group.createExpense("Exp1003", "Hotel", 1000, splits3, ExpenseSplitType.PERCENTAGE, userController.getUser("U3001"));

        // Show balance sheets
        System.out.println("\n═══ Balance Sheets ═══");
        for (User user : userController.getAllUsers()) {
            balanceSheetController.showBalanceSheetOfUser(user);
        }

        // Settlement — Greedy (minimum transactions)
        System.out.println("\n═══ Settlement: Greedy (Minimum Transactions) ═══");
        SettlementStrategy greedy = new GreedySettlementStrategy();
        List<Transaction> greedyTxns = greedy.settle(userController.getAllUsers());
        for (Transaction t : greedyTxns) {
            System.out.println("  " + t);
        }
        System.out.println("Total: " + greedyTxns.size() + " transactions\n");

        // Settlement — Pairwise
        System.out.println("═══ Settlement: Pairwise ═══");
        SettlementStrategy pairwise = new PairwiseSettlementStrategy();
        List<Transaction> pairwiseTxns = pairwise.settle(userController.getAllUsers());
        for (Transaction t : pairwiseTxns) {
            System.out.println("  " + t);
        }
        System.out.println("Total: " + pairwiseTxns.size() + " transactions");
    }

    public void setupUserAndGroup() {
        addUsersToSplitwiseApp();
        User user1 = userController.getUser("U1001");
        groupController.createNewGroup("G1001", "Outing with Friends", user1);
    }

    private void addUsersToSplitwiseApp() {
        userController.addUser(new User("U1001", "Alice"));
        userController.addUser(new User("U2001", "Bob"));
        userController.addUser(new User("U3001", "Charlie"));
        userController.addUser(new User("U4001", "Dave"));
    }

    public static void main(String[] args) {
        Splitwise splitwise = new Splitwise();
        splitwise.demo();
    }
}
