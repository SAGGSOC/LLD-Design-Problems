package atm.service;

import atm.enums.TransactionType;
import atm.model.Card;
import atm.model.CashDispenser;
import atm.model.Transaction;
import atm.state.AtmState;
import atm.state.IdleState;

/**
 * Context class for the State pattern.
 * Delegates all operations to the current AtmState.
 * States call setState(...) to transition.
 */
public class Atm {
    private final String atmId;
    private final String location;
    private final BankService bankService;
    private final CashDispenser cashDispenser;

    private AtmState currentState;
    private Card activeCard;

    public Atm(String atmId, String location, BankService bankService,
               CashDispenser cashDispenser) {
        this.atmId = atmId;
        this.location = location;
        this.bankService = bankService;
        this.cashDispenser = cashDispenser;
        this.currentState = new IdleState(this);
    }

    // ─── Public API — just delegates to current state ───

    public void insertCard(Card card)                    { currentState.insertCard(card); }
    public void enterPin(String pin)                     { currentState.enterPin(pin); }
    public void selectTransaction(TransactionType type)  { currentState.selectTransaction(type); }
    public Transaction withdraw(int amount)              { return currentState.withdraw(amount); }
    public Transaction deposit(double amount)            { return currentState.deposit(amount); }
    public Transaction checkBalance()                    { return currentState.checkBalance(); }
    public void ejectCard()                              { currentState.ejectCard(); }

    // ─── State management ───

    public void setState(AtmState newState) {
        System.out.println("[ATM] State transition: "
            + currentState.getStateName() + " → " + newState.getStateName());
        this.currentState = newState;
    }

    public void reset() {
        this.activeCard = null;
        this.currentState = new IdleState(this);
    }

    public void retainCard() {
        System.out.println("[ATM] Card " + activeCard.getCardNumber() + " retained for security");
        this.activeCard = null;
    }

    // ─── Accessors used by states ───

    public Card getActiveCard()             { return activeCard; }
    public void setActiveCard(Card card)    { this.activeCard = card; }
    public BankService getBankService()     { return bankService; }
    public CashDispenser getCashDispenser() { return cashDispenser; }
    public AtmState getCurrentState()       { return currentState; }
    public String getAtmId()                { return atmId; }
    public String getLocation()             { return location; }
}
