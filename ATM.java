import java.util.*;

/**
 * ATM — interview-ready, single-file (~170 lines).
 *
 * Core patterns:
 *   - State pattern (IDLE → CARD_INSERTED → AUTHENTICATED) enforces operation order
 *   - Greedy cash dispensing (denominations are canonical: 100/50/20/10/5)
 *   - Check dispenser BEFORE debiting account (if cash unavailable, user doesn't lose money)
 *
 * Out of scope: transfers, multiple currencies, hardware integration, fraud detection
 */
public class ATM {

    enum State { IDLE, CARD_INSERTED, AUTHENTICATED }

    // Denominations, largest first for greedy dispense
    static final int[] DENOMINATIONS = { 100, 50, 20, 10, 5 };

    static class Card {
        final String number;
        final String accountId;
        final int pin;
        Card(String number, String accountId, int pin) {
            this.number = number; this.accountId = accountId; this.pin = pin;
        }
    }

    static class Account {
        final String id;
        double balance;
        double dailyWithdrawn = 0;
        final double dailyLimit;
        Account(String id, double balance, double dailyLimit) {
            this.id = id; this.balance = balance; this.dailyLimit = dailyLimit;
        }
    }

    static class Bank {
        final Map<String, Account> accounts = new HashMap<>();
        final Map<String, Card> cards = new HashMap<>();

        void addAccount(Account a) { accounts.put(a.id, a); }
        void addCard(Card c)       { cards.put(c.number, c); }

        boolean verifyPin(Card card, int pin) { return card.pin == pin; }

        /** Synchronized — two ATMs could hit the same account. */
        synchronized void debit(String accountId, double amount) {
            Account a = accounts.get(accountId);
            if (amount > a.balance) throw new RuntimeException("Insufficient funds");
            if (a.dailyWithdrawn + amount > a.dailyLimit) {
                throw new RuntimeException("Daily limit exceeded");
            }
            a.balance -= amount;
            a.dailyWithdrawn += amount;
        }

        synchronized void credit(String accountId, double amount) {
            accounts.get(accountId).balance += amount;
        }

        double balance(String accountId) { return accounts.get(accountId).balance; }
    }

    /** Cash dispenser with greedy algorithm. */
    static class Dispenser {
        final Map<Integer, Integer> inventory = new HashMap<>();

        void load(int denomination, int count) {
            inventory.merge(denomination, count, Integer::sum);
        }

        /** Check if greedy dispense would succeed (without committing). */
        boolean canDispense(int amount) {
            int remaining = amount;
            for (int d : DENOMINATIONS) {
                int available = inventory.getOrDefault(d, 0);
                int needed = Math.min(available, remaining / d);
                remaining -= needed * d;
            }
            return remaining == 0;
        }

        /** Dispense amount, deduct from inventory, return breakdown. */
        Map<Integer, Integer> dispense(int amount) {
            if (!canDispense(amount)) throw new RuntimeException("Cannot dispense $" + amount);
            Map<Integer, Integer> breakdown = new LinkedHashMap<>();
            int remaining = amount;
            for (int d : DENOMINATIONS) {
                int available = inventory.getOrDefault(d, 0);
                int needed = Math.min(available, remaining / d);
                if (needed > 0) {
                    breakdown.put(d, needed);
                    inventory.merge(d, -needed, Integer::sum);
                    remaining -= needed * d;
                }
            }
            return breakdown;
        }
    }

    // ─── State-machine ATM ───

    static class AtmMachine {
        final Bank bank;
        final Dispenser dispenser;
        State state = State.IDLE;
        Card activeCard;
        int pinAttempts = 0;

        AtmMachine(Bank bank, Dispenser dispenser) { this.bank = bank; this.dispenser = dispenser; }

        void insertCard(Card card) {
            if (state != State.IDLE) throw new RuntimeException("Not IDLE");
            activeCard = card;
            pinAttempts = 0;
            state = State.CARD_INSERTED;
            System.out.println("  → CARD_INSERTED");
        }

        void enterPin(int pin) {
            if (state != State.CARD_INSERTED) throw new RuntimeException("Not CARD_INSERTED");
            if (bank.verifyPin(activeCard, pin)) {
                state = State.AUTHENTICATED;
                System.out.println("  → AUTHENTICATED");
                return;
            }
            pinAttempts++;
            if (pinAttempts >= 3) {
                System.out.println("  CARD RETAINED — 3 failed PIN attempts");
                reset();
                throw new RuntimeException("Card retained");
            }
            throw new RuntimeException("Invalid PIN, " + (3 - pinAttempts) + " attempts left");
        }

        Map<Integer, Integer> withdraw(int amount) {
            if (state != State.AUTHENTICATED) throw new RuntimeException("Not AUTHENTICATED");
            if (amount <= 0 || amount % 5 != 0) {
                throw new RuntimeException("Amount must be a positive multiple of $5");
            }
            // Pre-check BEFORE debiting — if dispenser can't make change, user doesn't lose money
            if (!dispenser.canDispense(amount)) {
                throw new RuntimeException("ATM cannot dispense this amount");
            }
            bank.debit(activeCard.accountId, amount);
            return dispenser.dispense(amount);
        }

        double checkBalance() {
            if (state != State.AUTHENTICATED) throw new RuntimeException("Not AUTHENTICATED");
            return bank.balance(activeCard.accountId);
        }

        void ejectCard() { reset(); }

        private void reset() { state = State.IDLE; activeCard = null; pinAttempts = 0; }
    }

    // ─── Demo ───

    public static void main(String[] args) {
        Bank bank = new Bank();
        bank.addAccount(new Account("ACC-1", 1500.00, 500.00));
        bank.addCard(new Card("CARD-1", "ACC-1", 1234));

        Dispenser dispenser = new Dispenser();
        dispenser.load(100, 5); dispenser.load(50, 5); dispenser.load(20, 10);
        dispenser.load(10, 5);  dispenser.load(5, 10);

        AtmMachine atm = new AtmMachine(bank, dispenser);
        Card card = bank.cards.get("CARD-1");

        // Happy path
        System.out.println("--- Happy path withdraw $265 ---");
        atm.insertCard(card);
        atm.enterPin(1234);
        Map<Integer, Integer> cash = atm.withdraw(265);
        System.out.println("Dispensed: " + cash + " | Balance: $" + atm.checkBalance());
        atm.ejectCard();

        // State violation
        System.out.println("\n--- Invalid state ---");
        try { atm.withdraw(100); } catch (Exception e) { System.out.println("  " + e.getMessage()); }

        // Bad PIN
        System.out.println("\n--- Wrong PIN 3 times ---");
        atm.insertCard(card);
        for (int i = 0; i < 3; i++) {
            try { atm.enterPin(0000); } catch (Exception e) { System.out.println("  " + e.getMessage()); }
        }

        // Daily limit
        System.out.println("\n--- Daily limit check ---");
        atm.insertCard(card);
        atm.enterPin(1234);
        try { atm.withdraw(300); } catch (Exception e) { System.out.println("  " + e.getMessage()); }
        atm.ejectCard();
    }
}
