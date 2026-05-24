package atm.service;

import atm.enums.TransactionStatus;
import atm.enums.TransactionType;
import atm.model.Account;
import atm.model.Card;
import atm.model.Transaction;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates the bank's backend. Holds accounts, cards, and transaction history.
 */
public class BankService {
    private final Map<String, Account> accountsById = new ConcurrentHashMap<>();
    private final Map<String, Card> cardsByNumber = new ConcurrentHashMap<>();
    private final List<Transaction> transactionLog = new ArrayList<>();

    public void addAccount(Account account) {
        accountsById.put(account.getAccountId(), account);
    }

    public void addCard(Card card) {
        cardsByNumber.put(card.getCardNumber(), card);
    }

    public Account getAccount(String accountId) {
        Account account = accountsById.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        return account;
    }

    public Card getCard(String cardNumber) {
        return cardsByNumber.get(cardNumber);
    }

    public boolean verifyPin(Card card, String inputPin) {
        if (card == null) return false;
        String inputHash = hash(inputPin);
        return constantTimeEquals(inputHash, card.getPinHash());
    }

    public Transaction recordTransaction(String accountId, TransactionType type,
                                          double amount, TransactionStatus status,
                                          String details) {
        String txnId = "TXN-" + UUID.randomUUID().toString().substring(0, 8);
        Transaction txn = new Transaction(txnId, accountId, type, amount, status, details);
        synchronized (transactionLog) {
            transactionLog.add(txn);
        }
        return txn;
    }

    public Transaction recordFailedTransaction(String accountId, TransactionType type,
                                                 double amount, TransactionStatus status,
                                                 String reason) {
        return recordTransaction(accountId, type, amount, status, reason);
    }

    public List<Transaction> getTransactionHistory(String accountId) {
        synchronized (transactionLog) {
            List<Transaction> filtered = new ArrayList<>();
            for (Transaction txn : transactionLog) {
                if (txn.getAccountId().equals(accountId)) filtered.add(txn);
            }
            return filtered;
        }
    }

    /** SHA-256 for PIN hashing. In production: bcrypt or PBKDF2 with salt. */
    public static String hash(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashed = md.digest(plain.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hash failed", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
