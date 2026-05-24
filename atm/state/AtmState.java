package atm.state;

import atm.enums.TransactionType;
import atm.model.Card;
import atm.model.Transaction;

/**
 * State interface — every state implements these operations.
 * States that don't support a given operation throw InvalidStateException.
 */
public interface AtmState {
    void insertCard(Card card);
    void enterPin(String pin);
    void selectTransaction(TransactionType type);
    Transaction withdraw(int amount);
    Transaction deposit(double amount);
    Transaction checkBalance();
    void ejectCard();

    String getStateName();
}
