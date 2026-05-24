package atm;

import atm.enums.CardType;
import atm.enums.Denomination;
import atm.enums.TransactionType;
import atm.exception.AtmException;
import atm.exception.InvalidPinException;
import atm.exception.InvalidStateException;
import atm.model.Account;
import atm.model.Card;
import atm.model.CashDispenser;
import atm.model.Transaction;
import atm.service.Atm;
import atm.service.BankService;

public class AtmDemo {

    public static void main(String[] args) {
        // ─── Setup ───
        BankService bank = new BankService();

        // Create accounts
        Account aliceAccount = new Account("ACC-001", "Alice", 1500.00, 500.00);
        Account bobAccount = new Account("ACC-002", "Bob", 100.00, 500.00);
        bank.addAccount(aliceAccount);
        bank.addAccount(bobAccount);

        // Create cards with hashed PINs
        bank.addCard(new Card("CARD-4532-1111", "ACC-001", "Alice",
            CardType.DEBIT, BankService.hash("1234")));
        bank.addCard(new Card("CARD-4532-2222", "ACC-002", "Bob",
            CardType.DEBIT, BankService.hash("9999")));

        // Load ATM cash dispenser
        CashDispenser dispenser = new CashDispenser();
        dispenser.loadCash(Denomination.HUNDRED, 10);  // $1000
        dispenser.loadCash(Denomination.FIFTY, 10);    // $500
        dispenser.loadCash(Denomination.TWENTY, 20);   // $400
        dispenser.loadCash(Denomination.TEN, 10);      // $100
        dispenser.loadCash(Denomination.FIVE, 10);     // $50
        System.out.println("[SETUP] ATM loaded with $" + dispenser.getTotalCash());
        System.out.println();

        Atm atm = new Atm("ATM-001", "Downtown Branch", bank, dispenser);
        Card aliceCard = bank.getCard("CARD-4532-1111");
        Card bobCard = bank.getCard("CARD-4532-2222");

        // ─── Scenario 1: Happy path withdrawal ───
        System.out.println("=== Scenario 1: Alice withdraws $260 ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        atm.selectTransaction(TransactionType.WITHDRAW);
        Transaction withdrawal = atm.withdraw(260);
        System.out.println("  → " + withdrawal);
        System.out.println("  → Alice's new balance: $" + aliceAccount.getBalance());
        atm.ejectCard();
        System.out.println();

        // ─── Scenario 2: Invalid PIN attempts ───
        System.out.println("=== Scenario 2: Wrong PIN 3 times ===");
        atm.insertCard(bobCard);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                atm.enterPin("0000");
            } catch (InvalidPinException e) {
                System.out.println("  Attempt " + attempt + ": " + e.getMessage());
            }
        }
        System.out.println("  Current state: " + atm.getCurrentState().getStateName());
        System.out.println();

        // ─── Scenario 3: State violation ───
        System.out.println("=== Scenario 3: Invalid state transition ===");
        try {
            atm.withdraw(50);  // ATM is IDLE, can't withdraw directly
        } catch (InvalidStateException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        System.out.println();

        // ─── Scenario 4: Check balance ───
        System.out.println("=== Scenario 4: Alice checks balance ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        Transaction balanceCheck = atm.checkBalance();
        System.out.println("  → " + balanceCheck);
        atm.ejectCard();
        System.out.println();

        // ─── Scenario 5: Deposit ───
        System.out.println("=== Scenario 5: Alice deposits $500 ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        Transaction deposit = atm.deposit(500.00);
        System.out.println("  → " + deposit);
        System.out.println("  → Alice's balance: $" + aliceAccount.getBalance());
        atm.ejectCard();
        System.out.println();

        // ─── Scenario 6: Insufficient funds ───
        System.out.println("=== Scenario 6: Bob tries to withdraw $200 (only has $100) ===");
        // Reset Bob's card retention for this demo
        bank.addCard(new Card("CARD-4532-2222", "ACC-002", "Bob",
            CardType.DEBIT, BankService.hash("9999")));
        atm.insertCard(bobCard);
        atm.enterPin("9999");
        Transaction failed = atm.withdraw(200);
        System.out.println("  → " + failed);
        atm.ejectCard();
        System.out.println();

        // ─── Scenario 7: Daily limit exceeded ───
        System.out.println("=== Scenario 7: Alice tries to exceed daily limit ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        // She's already withdrawn $260 today. Limit is $500. Try $300 — should fail.
        Transaction overLimit = atm.withdraw(300);
        System.out.println("  → " + overLimit);
        atm.ejectCard();
        System.out.println();

        // ─── Scenario 8: Amount not multiple of $5 ───
        System.out.println("=== Scenario 8: Odd amount rejected ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        try {
            atm.withdraw(23);  // Not a multiple of $5
        } catch (AtmException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        atm.ejectCard();
        System.out.println();

        // ─── Scenario 9: Dispenser breakdown ───
        System.out.println("=== Scenario 9: Cash dispenser breakdown ===");
        atm.insertCard(aliceCard);
        atm.enterPin("1234");
        Transaction withdrawal240 = atm.withdraw(235);
        System.out.println("  → " + withdrawal240);
        System.out.println("  Remaining inventory: " + dispenser.getInventory());
        atm.ejectCard();
    }
}
