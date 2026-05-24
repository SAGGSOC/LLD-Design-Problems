package atm.state;

import atm.model.Card;
import atm.service.Atm;

/**
 * ATM waiting for a card. Only operation allowed: insertCard.
 */
public class IdleState extends AbstractAtmState {

    public IdleState(Atm atm) {
        super(atm);
    }

    @Override
    public void insertCard(Card card) {
        System.out.println("[ATM] Card " + card.getCardNumber() + " inserted");
        atm.setActiveCard(card);
        atm.setState(new CardInsertedState(atm));
    }

    @Override
    public String getStateName() { return "IDLE"; }
}
