package atm.state;

import atm.enums.TransactionStatus;
import atm.enums.TransactionType;
import atm.exception.AtmException;
import atm.model.Account;
import atm.model.Transaction;
import atm.service.Atm;

/**
 * PIN verified. User can select a transaction or eject card.
 * Withdraw/deposit/checkBalance are allowed and route to BankService.
 */
public class AuthenticatedState extends AbstractAtmState {

    public AuthenticatedState(Atm atm) {
        super(atm);
    }

    @Override
    public void selectTransaction(TransactionType type) {
        System.out.println("[ATM] Transaction selected: " + type);
        // In a state-driven UI, each transaction type might have its own
        // sub-state (e.g., AWAITING_AMOUNT). For simplicity, we stay here
        // and the user directly calls withdraw/deposit/checkBalance next.
    }

    @Override
    public Transaction withdraw(int amount) {
        if (amount <= 0) {
            throw new AtmException("Amount must be positive");
        }
        if (amount % 5 != 0) {
            throw new AtmException("Amount must be a multiple of $5");
        }

        // Check ATM cash availability before debiting
        if (!atm.getCashDispenser().canDispense(amount)) {
            Transaction failed = atm.getBankService().recordFailedTransaction(
                atm.getActiveCard().getAccountId(), TransactionType.WITHDRAW, amount,
                TransactionStatus.ATM_OUT_OF_CASH, "ATM cannot dispense this amount");
            return failed;
        }

        try {
            // Debit account first, then dispense cash
            Account account = atm.getBankService().getAccount(atm.getActiveCard().getAccountId());
            account.debit(amount);
            atm.getCashDispenser().dispense(amount);

            Transaction txn = atm.getBankService().recordTransaction(
                account.getAccountId(), TransactionType.WITHDRAW, amount,
                TransactionStatus.SUCCESS, "Cash dispensed");

            System.out.println("[ATM] Dispensed $" + amount);
            return txn;
        } catch (AtmException e) {
            return atm.getBankService().recordFailedTransaction(
                atm.getActiveCard().getAccountId(), TransactionType.WITHDRAW, amount,
                TransactionStatus.INSUFFICIENT_FUNDS, e.getMessage());
        }
    }

    @Override
    public Transaction deposit(double amount) {
        if (amount <= 0) {
            throw new AtmException("Amount must be positive");
        }
        Account account = atm.getBankService().getAccount(atm.getActiveCard().getAccountId());
        account.credit(amount);

        Transaction txn = atm.getBankService().recordTransaction(
            account.getAccountId(), TransactionType.DEPOSIT, amount,
            TransactionStatus.SUCCESS, "Deposit accepted");

        System.out.println("[ATM] Deposited $" + amount);
        return txn;
    }

    @Override
    public Transaction checkBalance() {
        Account account = atm.getBankService().getAccount(atm.getActiveCard().getAccountId());
        double balance = account.getBalance();

        Transaction txn = atm.getBankService().recordTransaction(
            account.getAccountId(), TransactionType.BALANCE_INQUIRY, 0,
            TransactionStatus.SUCCESS, "Balance: $" + balance);

        System.out.println("[ATM] Balance: $" + balance);
        return txn;
    }

    @Override
    public void ejectCard() {
        System.out.println("[ATM] Card ejected, session ended");
        atm.reset();
    }

    @Override
    public String getStateName() { return "AUTHENTICATED"; }
}
