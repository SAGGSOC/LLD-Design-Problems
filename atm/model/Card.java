package atm.model;

import atm.enums.CardType;

public class Card {
    private final String cardNumber;
    private final String accountId;
    private final String customerName;
    private final CardType type;
    private final String pinHash;  // never store plaintext PIN

    public Card(String cardNumber, String accountId, String customerName,
                CardType type, String pinHash) {
        this.cardNumber = cardNumber;
        this.accountId = accountId;
        this.customerName = customerName;
        this.type = type;
        this.pinHash = pinHash;
    }

    public String getCardNumber()   { return cardNumber; }
    public String getAccountId()    { return accountId; }
    public String getCustomerName() { return customerName; }
    public CardType getType()       { return type; }
    public String getPinHash()      { return pinHash; }
}
