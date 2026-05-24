package atm.model;

import atm.exception.InsufficientFundsException;

public class Account {
    private final String accountId;
    private final String customerName;
    private double balance;
    private final double dailyWithdrawLimit;
    private double withdrawnToday;

    public Account(String accountId, String customerName, double initialBalance,
                   double dailyWithdrawLimit) {
        this.accountId = accountId;
        this.customerName = customerName;
        this.balance = initialBalance;
        this.dailyWithdrawLimit = dailyWithdrawLimit;
        this.withdrawnToday = 0;
    }

    public synchronized void debit(double amount) {
        if (amount > balance) {
            throw new InsufficientFundsException(
                "Balance $" + balance + " insufficient for $" + amount);
        }
        if (withdrawnToday + amount > dailyWithdrawLimit) {
            throw new InsufficientFundsException(
                "Daily withdrawal limit $" + dailyWithdrawLimit + " exceeded");
        }
        balance -= amount;
        withdrawnToday += amount;
    }

    public synchronized void credit(double amount) {
        balance += amount;
    }

    public synchronized double getBalance() { return balance; }
    public String getAccountId()            { return accountId; }
    public String getCustomerName()         { return customerName; }
    public double getDailyWithdrawLimit()   { return dailyWithdrawLimit; }
    public double getWithdrawnToday()       { return withdrawnToday; }

    public void resetDailyCounter() { this.withdrawnToday = 0; }
}
