package atm.model;

import atm.enums.TransactionStatus;
import atm.enums.TransactionType;
import java.time.Instant;

public class Transaction {
    private final String transactionId;
    private final String accountId;
    private final TransactionType type;
    private final double amount;
    private final TransactionStatus status;
    private final Instant timestamp;
    private final String details;

    public Transaction(String transactionId, String accountId, TransactionType type,
                       double amount, TransactionStatus status, String details) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.status = status;
        this.timestamp = Instant.now();
        this.details = details;
    }

    public String getTransactionId()      { return transactionId; }
    public String getAccountId()          { return accountId; }
    public TransactionType getType()      { return type; }
    public double getAmount()             { return amount; }
    public TransactionStatus getStatus()  { return status; }
    public Instant getTimestamp()         { return timestamp; }
    public String getDetails()            { return details; }

    @Override
    public String toString() {
        return String.format("[%s] %s $%.2f %s — %s",
            transactionId, type, amount, status, details);
    }
}
