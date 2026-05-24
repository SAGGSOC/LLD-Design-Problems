import atm.enums.TransactionType;
import atm.state.IdleState;
public class Card{
    static int PIN_NUMBER = 112211;
    private int cardNumber;
    private int cvv;
    private int expiryDate;
    private int holderName;
    private UserBankAccount bankAccount;
    public boolean isCorrectPINEntered(int pin) {
        return pin == PIN_NUMBER;
    }
    public int getBankBalance(){
        return bankAccount.balance;
    }
   public void deductBankBalance(int amount) {
        bankAccount.withdrawalBalance(amount);
    }
    public UserBankAccount getBankAccount() {
        return bankAccount;
    }
    public void setBankAccount(UserBankAccount bankAccount){
        this.bankAccount = bankAccount;
    }    
}
public class User{
    Card card;
    UserBankAccount bankAccount;
    public Card getCard(){
        return card;
    }
    public void setCard(Card card) {
        this.card = card;
    }
}
public class UserBankAccount{
    int balance;
    public void withdrawalBalance(int amount){
        balance -= amount;
    }
    public int getBalance() {
        return balance;
    }
    public void setBalance(int balance) {
        this.balance = balance;
    }
}
public class CashWithDrawalState extends ATMState{
    public CashWithdrawalState() {
        System.out.println("Please enter the Withdrawal Amount");
    }
    public void cashWithDrawal(ATM atmObject, Card card, int withdrawalAmountRequest){
        if(atmObject.getAtmBalance() <withdrawalAmountRequest){
            SOPln();
            exit(atmObject);
        } else if(card.getBankBalance() < withdrawalAmountRequest){
            System.out.println("Insufficient Balance in your account");
            exit(atmObject);
        } else {
            card.deductBankBalance(withdrawalAmountRequest);
            atmObject.deductATMBalance(withdrawalAmountRequest);
            CashWithdrawProcessor withdrawProcessor =
                    new TwoThousandWithdrawProcessor(new FiveHundredWithdrawProcessor(new OneHundredWithdrawProcessor(null)));

            withdrawProcessor.withdraw(atmObject, withdrawalAmountRequest);
            exit(atmObject);
        }

    }
    @Override
    public void exit(ATM atmObject) {
        returnCard();
        atmObject.setCurrentATMState(new IdleState());
        System.out.println("Exit happens");
    }
    @Override
    public void returnCard() {
        System.out.println("Please collect your card");
    }
}
public class CheckBalanceState extends AtmState{
    public CheckBalanceState() {
    }
    @Override
    public void displayBalance(Atm atm, Card card){
        System.out.println("Your Balance is: " + card.getBankBalance());
        exit(atm);
    }
    @Override
    public void exit(ATM atmObject) {
        returnCard();
        atmObject.setCurrentATMState(new IdleState());
        System.out.println("Exit happens");
    }

    public void returnCard() {
        System.out.println("Please collect your card");
    }

}
public class IdleState extends ATMState {

    @Override
    public void insertCard(ATM atm, Card card) {
        System.out.println("Card is inserted");
        atm.setCurrentATMState(new HasCardState());
    }
}
public class HasCardState extends AtmState{
    public HasCardState(){
        Sopln();
    }
    @Override
    public void authenticatePin(ATM atm, Card card, int pin) {
        boolean isCorrectPinEntered = card.isCorrectPINEntered(pin);

        if (isCorrectPinEntered) {
            atm.setCurrentATMState(new SelectOperationState());
        } else {
            System.out.println("Invalid PIN Number");
            exit(atm);
        }
    }
    @Override
    public void exit(ATM atm) {
        returnCard();
        atm.setCurrentATMState(new IdleState());
        System.out.println("Exit happens");
    }
    @Override
    public void returnCard() {
        System.out.println("Please collect your card");
    }
}
public class SelectOperationState extends AtmState{
    @Override
    public void selectOperation(Atm atmObject,Card card, TransactionType txnType){
        switch (txnType) {
            case CASH_WITHDRAWAL:
                atmObject.setCurrentATMState(new CashWithdrawalState());
                break;
            case BALANCE_CHECK:
                atmObject.setCurrentATMState(new CheckBalanceState());
                break;
            default: {
                System.out.println("Invalid Option");
                exit(atmObject);
            }
        }
    }
    @Override
    public void exit(ATM atmObject) {
        returnCard();
        atmObject.setCurrentATMState(new IdleState());
        System.out.println("Exit happens");
    }
    @Override
    public void returnCard() {
        System.out.println("Please collect your card");
    }
    private void showOperations() {
        System.out.println("Please select the Operation");
        TransactionType.showAllTransactionTypes();
    }
}
public enum TransactionType{
    CASH_WITHDRAWAL,BALANCE_CHECK;
    public static void showAllTransactionTypes() {
        for (TransactionType type : TransactionType.values()) {
            System.out.println(type.name());
        }
    }
}
public abstract class CashWithdrawProcessor(){
    CashWithdrawProcessor nextCashWithdrawProcessor;
    CashWithdrawProcessor(CashWithdrawProcessor cashWithdrawProcessor){
        this.nextCashWithdrawalProcessor = cashWithdrawalProcessor;
    }
    public void withdraw(ATM atm, int remainingAmt){
        if (nextCashWithdrawalProcessor != null) {
            nextCashWithdrawalProcessor.withdraw(atm, remainingAmount);
        }
    }
}
public class FiveHundredWithdrawProcessor extends CashWithdrawProcessor{
    public FiveHundredWithdrawProcessor(CashWithdrawProcessor cashWithdrawProcessor){
        super(cashWithdrawProcessor);
    }
    public void withdraw(ATM atm, int remainingAmount) {
        int required = remainingAmount/500;
        int balance = remainingAmount%100;

        if(required <= atm.getNoOfFiveHundredNotes()){
            atm.deductFiveHundredNotes(required);
        }
    }
}
public abstract class AtmState{
    public void insertCard(ATM atm, Card card){
        System.out.println("OOPS!! Something went wrong");
    }
    public void authenticatePin(ATM atm, Card card, int pin){
        System.out.println("OOPS!! Something went wrong");
    }
    public void selectOperation(ATM atm, Card card, int pin){
        System.out.println("OOPS!! Something went wrong");
    }
    public void cashWithDrawal(ATM atm, Card card, TransactionType txnType){

    }
}

public class ATMv2 {
    private static ATM atmObject = new ATM();
    AtmState currenAtmState;
    int noOfTwoThousandNotes;
    int noOfFiveHundredNotes;
    int noOfOneHundredNotes;
    private int atmBalance;

    private ATM(){

    }
    public static ATM getATMObject(){
        atmObject.setCurrentATMState(new IdleState());
    }
    public ATMState getCurrentATMState(){
        return currentATMState;
    }
    public void setCurrentATMState(AtmState currenAtmState){
        this.currenAtmState = currenAtmState;
    }
    public int getAtmBalance() {
        return atmBalance;
    }
    public void setAtmBalance(int atmBalance, int noOfFiveHundredNotes, int noOfTwoThousandNotes, int noOfOneHundredNotes){
        this.atmBalance = atmBalance;
        this.noOfFiveHundredNotes = noOfFiveHundredNotes;
        this.noOfTwoThousandNotes = noOfTwoThousandNotes;
        this.noOfOneHundredNotes = noOfOneHundredNotes;
    }
    public int getNoOfTwoThousandNotes() {
        return noOfTwoThousandNotes;
    }

    public int getNoOfFiveHundredNotes() {
        return noOfFiveHundredNotes;
    }

    public int getNoOfOneHundredNotes() {
        return noOfOneHundredNotes;
    }
    public void deductFiveHundredNotes(int noOfFiveHundredNotesToDeduct){
        this.noOfFiveHundredNotes -= noOfFiveHundredNotesToDeduct;
    }
    public void deductTwoThousandNotes(int noOfTwoThousandNotesToDeduct){
        this.noOfTwoThousandNotes -= noOfTwoThousandNotesToDeduct;
    }
    public void deductOneHundredNotes(int noOfOneHundredNotesToDeduct){
        this.noOfOneHundredNotes -= noOfOneHundredNotesToDeduct;
    }
}
