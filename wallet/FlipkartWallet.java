import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Flipkart Payment Wallet — In-Memory (Interview Style)
 *
 * Features:
 *   - Load money from sources (UPI, CreditCard, DebitCard)
 *   - Send money between users
 *   - Get wallet balance
 *   - Transaction history with sort (time/amount) and filter (send/receive/all)
 *
 * Concurrency:
 *   - ConcurrentHashMap for user wallets
 *   - ReentrantLock per user for balance mutations (load/send)
 *   - Lock ordering for sendMoney (both sender + receiver must be locked)
 *
 * Design:
 *   - Each transaction stores: timestamp, type, counterparty, amount
 *   - Balance = sum(credits) - sum(debits), computed from transaction list
 *   - sendMoney creates TWO transactions (SEND for sender, RECEIVE for receiver)
 */
public class FlipkartWallet {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    enum TransactionType { LOAD, SEND, RECEIVE }

    static class Transaction {
        final long timestamp;
        final TransactionType type;
        final String counterparty;
        final long amount;

        Transaction(long timestamp, TransactionType type, String counterparty, long amount) {
            this.timestamp = timestamp;
            this.type = type;
            this.counterparty = counterparty;
            this.amount = amount;
        }

        boolean isCredit() { return type == TransactionType.LOAD || type == TransactionType.RECEIVE; }
        boolean isDebit() { return type == TransactionType.SEND; }

        @Override
        public String toString() {
            return "time=" + timestamp + "|type=" + type + "|counterparty=" + counterparty + "|amount=" + amount;
        }
    }

    static class UserWallet {
        final String userId;
        final List<Transaction> transactions;
        final ReentrantLock lock;

        UserWallet(String userId) {
            this.userId = userId;
            this.transactions = new ArrayList<>();
            this.lock = new ReentrantLock();
        }

        long getBalance() {
            long balance = 0;
            for (Transaction t : transactions) {
                if (t.isCredit()) balance += t.amount;
                else if (t.isDebit()) balance -= t.amount;
            }
            return balance;
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Map<String, UserWallet> wallets = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════

    public FlipkartWallet(List<String> registeredUserIds) {
        for (String userId : registeredUserIds) {
            wallets.put(userId, new UserWallet(userId));
        }
    }

    // ═══════════════════════════════════════════════
    // 1. Load Money
    // ═══════════════════════════════════════════════

    public boolean loadMoney(String userId, long amount, String source, long timestamp) {
        if (amount <= 0) return false;

        UserWallet wallet = wallets.get(userId);
        if (wallet == null) return false;

        wallet.lock.lock();
        try {
            wallet.transactions.add(new Transaction(timestamp, TransactionType.LOAD, source, amount));
        } finally {
            wallet.lock.unlock();
        }
        return true;
    }

    // ═══════════════════════════════════════════════
    // 2. Send Money
    // ═══════════════════════════════════════════════

    public boolean sendMoney(String fromUserId, String toUserId, long amount, long timestamp) {
        if (amount <= 0) return false;

        UserWallet fromWallet = wallets.get(fromUserId);
        UserWallet toWallet = wallets.get(toUserId);
        if (fromWallet == null || toWallet == null) return false;
        if (fromUserId.equals(toUserId)) return false;

        // Lock ordering by userId to prevent deadlock
        // (same pattern as inventory warehouse transfer)
        UserWallet firstLock = fromUserId.compareTo(toUserId) < 0 ? fromWallet : toWallet;
        UserWallet secondLock = fromUserId.compareTo(toUserId) < 0 ? toWallet : fromWallet;

        firstLock.lock.lock();
        try {
            secondLock.lock.lock();
            try {
                // Check sufficient balance
                if (fromWallet.getBalance() < amount) return false;

                // Create paired transactions (same timestamp)
                fromWallet.transactions.add(new Transaction(timestamp, TransactionType.SEND, toUserId, amount));
                toWallet.transactions.add(new Transaction(timestamp, TransactionType.RECEIVE, fromUserId, amount));
                return true;
            } finally {
                secondLock.lock.unlock();
            }
        } finally {
            firstLock.lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // 3. Get Balance
    // ═══════════════════════════════════════════════

    public long getBalance(String userId) {
        UserWallet wallet = wallets.get(userId);
        if (wallet == null) return -1;

        wallet.lock.lock();
        try {
            return wallet.getBalance();
        } finally {
            wallet.lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // 4. Get Transaction History
    // ═══════════════════════════════════════════════

    public List<String> getTransactionHistory(String userId, String sortBy, String filterBy) {
        UserWallet wallet = wallets.get(userId);
        if (wallet == null) return Collections.emptyList();

        List<Transaction> filtered;

        wallet.lock.lock();
        try {
            // Filter
            switch (filterBy) {
                case "send":
                    filtered = wallet.transactions.stream()
                        .filter(t -> t.type == TransactionType.SEND)
                        .collect(Collectors.toList());
                    break;
                case "receive":
                    filtered = wallet.transactions.stream()
                        .filter(t -> t.type == TransactionType.RECEIVE)
                        .collect(Collectors.toList());
                    break;
                case "all":
                default:
                    filtered = new ArrayList<>(wallet.transactions);
                    break;
            }
        } finally {
            wallet.lock.unlock();
        }

        // Sort (stable sort preserves insertion order on ties)
        switch (sortBy) {
            case "time":
                // Ascending by timestamp (stable — preserves insertion order on tie)
                filtered.sort(Comparator.comparingLong(t -> t.timestamp));
                break;
            case "amount":
                // Descending by amount
                filtered.sort((a, b) -> Long.compare(b.amount, a.amount));
                break;
        }

        return filtered.stream().map(Transaction::toString).collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        FlipkartWallet wallet = new FlipkartWallet(Arrays.asList("user-1", "user-2"));

        // ─── Example 1 ───
        System.out.println("═══ Example 1: Load, Send, Balance, History ═══\n");

        System.out.println("loadMoney user-1 500 UPI t=1000: " +
            wallet.loadMoney("user-1", 500, "UPI", 1000));  // true

        System.out.println("sendMoney user-1→user-2 200 t=1010: " +
            wallet.sendMoney("user-1", "user-2", 200, 1010));  // true

        System.out.println("sendMoney user-1→user-2 400 t=1020: " +
            wallet.sendMoney("user-1", "user-2", 400, 1020));  // false (insufficient)

        System.out.println("Balance user-1: " + wallet.getBalance("user-1"));  // 300
        System.out.println("Balance user-2: " + wallet.getBalance("user-2"));  // 200

        System.out.println("\nHistory user-1 (time, all):");
        wallet.getTransactionHistory("user-1", "time", "all").forEach(s -> System.out.println("  " + s));

        System.out.println("\nHistory user-2 (time, receive):");
        wallet.getTransactionHistory("user-2", "time", "receive").forEach(s -> System.out.println("  " + s));

        // ─── Example 2: Sort by amount ───
        System.out.println("\n═══ Example 2: Sort by Amount ═══\n");

        wallet.loadMoney("user-1", 50, "CreditCard", 1030);
        System.out.println("Balance user-1: " + wallet.getBalance("user-1"));  // 350

        System.out.println("\nHistory user-1 (amount, all):");
        wallet.getTransactionHistory("user-1", "amount", "all").forEach(s -> System.out.println("  " + s));

        // ─── Example 3: Filter send/receive ───
        System.out.println("\n═══ Example 3: Filter Send/Receive ═══\n");

        wallet.loadMoney("user-1", 1000, "DebitCard", 2000);
        wallet.sendMoney("user-1", "user-2", 700, 2010);

        System.out.println("Balance user-1: " + wallet.getBalance("user-1"));  // 650
        System.out.println("Balance user-2: " + wallet.getBalance("user-2"));  // 900

        System.out.println("\nHistory user-1 (time, send):");
        wallet.getTransactionHistory("user-1", "time", "send").forEach(s -> System.out.println("  " + s));

        System.out.println("\nHistory user-2 (time, receive):");
        wallet.getTransactionHistory("user-2", "time", "receive").forEach(s -> System.out.println("  " + s));

        // ─── Edge cases ───
        System.out.println("\n═══ Edge Cases ═══\n");
        System.out.println("loadMoney invalid user: " + wallet.loadMoney("ghost", 100, "UPI", 3000));  // false
        System.out.println("sendMoney insufficient: " + wallet.sendMoney("user-2", "user-1", 5000, 3010));  // false
        System.out.println("getBalance invalid: " + wallet.getBalance("ghost"));  // -1
        System.out.println("getHistory invalid: " + wallet.getTransactionHistory("ghost", "time", "all"));  // []
    }
}
