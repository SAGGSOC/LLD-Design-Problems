package atm.state;

import atm.enums.TransactionType;
import atm.exception.InvalidStateException;
import atm.model.Card;
import atm.model.Transaction;
import atm.service.Atm;

/**
 * Base class that rejects all operations by default.
 * Concrete states override only the operations they support.
 */
public abstract class AbstractAtmState implements AtmState {
    protected final Atm atm;

    protected AbstractAtmState(Atm atm) {
        this.atm = atm;
    }

    @Override
    public void insertCard(Card card) {
        throw new InvalidStateException("Cannot insert card in state " + getStateName());
    }

    @Override
    public void enterPin(String pin) {
        throw new InvalidStateException("Cannot enter PIN in state " + getStateName());
    }

    @Override
    public void selectTransaction(TransactionType type) {
        throw new InvalidStateException("Cannot select transaction in state " + getStateName());
    }

    @Override
    public Transaction withdraw(int amount) {
        throw new InvalidStateException("Cannot withdraw in state " + getStateName());
    }

    @Override
    public Transaction deposit(double amount) {
        throw new InvalidStateException("Cannot deposit in state " + getStateName());
    }

    @Override
    public Transaction checkBalance() {
        throw new InvalidStateException("Cannot check balance in state " + getStateName());
    }

    @Override
    public void ejectCard() {
        throw new InvalidStateException("Cannot eject card in state " + getStateName());
    }
}
