package atm.state;

import atm.exception.InvalidPinException;
import atm.service.Atm;

/**
 * Card is inserted, waiting for PIN. 3-strikes policy.
 */
public class CardInsertedState extends AbstractAtmState {
    private static final int MAX_PIN_ATTEMPTS = 3;
    private int pinAttempts = 0;

    public CardInsertedState(Atm atm) {
        super(atm);
    }

    @Override
    public void enterPin(String pin) {
        boolean valid = atm.getBankService().verifyPin(atm.getActiveCard(), pin);
        if (valid) {
            System.out.println("[ATM] PIN verified");
            atm.setState(new AuthenticatedState(atm));
            return;
        }

        pinAttempts++;
        if (pinAttempts >= MAX_PIN_ATTEMPTS) {
            System.out.println("[ATM] Too many failed PIN attempts — card retained");
            atm.retainCard();
            atm.reset();
            throw new InvalidPinException("Card retained after 3 failed attempts");
        }
        throw new InvalidPinException(
            "Invalid PIN, " + (MAX_PIN_ATTEMPTS - pinAttempts) + " attempts remaining");
    }

    @Override
    public void ejectCard() {
        System.out.println("[ATM] Card ejected");
        atm.reset();
    }

    @Override
    public String getStateName() { return "CARD_INSERTED"; }
}
