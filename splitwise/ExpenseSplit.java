import java.util.ArrayList;
import java.util.*;

class EqualExpenseSplit implements ExpenseSplit{
    @Override
    public void validateSplitRequest(List<Split> splitList, double totalAmount){
        double amountShouldBePresent = totalAmount/splitList.size();
        for(Split split: splitList){
            if(split.getAmountOwe() != amountShouldBePresent){
                //throw exception
            }
        }
    }
}
public interface ExpenseSplit{
    public void validateSplitRequest(List<Split> splitList, double totalAmount);
}
class PercentageExpenseSplit implements ExpenseSplit{
    @Override
    public void validateSplitRequest(List<Split> splitList, double totalAmount){

    }
}
class UnequalExpenseSplit implements ExpenseSplit{
    @Override
    public void validateSplitRequest(List<Split> splitList, double totalAmount){

    }
}
class Split{
    User user;
    double amountOwe;
    public Split(User user, double amountOwe){
        this.user = user;
        this.amountOwe = amountOwe;
    }
    public User getUser() {
        return user;
    }
    public void setUser(User user) {
        this.user = user;
    }
    public double getAmountOwe() {
        return amountOwe;
    }
    public void setAmountOwe(double amountOwe) {
        this.amountOwe = amountOwe;
    }
}
public interface ExpenseSplit {
    public void validateSplitRequest(List<Split> splitList, double totalAmount);
}
public enum ExpenseSplitType{
    EQUAL,
    UNEQUAL,
    PERCENTAGE
}
public class Expense{
    String expenseId;
    String description;
    double expenseAmount;
    User paidByUser;
    ExpenseSplitType splitType;
    List<Split> splitDetails = new ArrayList<>();

    public Expense(String expenseId, double expenseAmount, String description,User paidByUser,ExpenseSplitType splitType, List<Split> splitDetails){
        this.expenseId = expenseId;
        this.description = description;
        this.paidByUser = paidByUser;
        this.splitType = splitType;
        this.splitDetails = splitDetails;
        this.expenseAmount = expenseAmount;
    }
}
public class ExpenseController{
    BalanceSheetController BalanceSheetController;
    public ExpenseController(){
        BalanceSheetController = new BalanceSheetController();
    }
    public Expense createExpense(String expenseId,String description, double expenseAmount, List<Split> splitDetails, ExpenseSplitType splitType, User paidByUser){
        ExpenseSplit expenseSplit = SplitFactory.getSplitObject(splitType);
        expenseSplit.validateSplitRequest(splitDetails, expenseAmount);


    }   
}
class SplitFactory{
    public static ExpenseSplit getSplitObject(ExpenseSplitType splitType){
        switch(splitType){
            case EQUAL:
                return new EqualExpenseSplit();
            case UNEQUAL:
                return new UnequalExpenseSplit();
            case PERCENTAGE:
                return new PercentageExpenseSplit();
            default:
                return null;
        }
    }
}
class Balance{
    double amountOwe;
    double amountGetBack;
    public double getAmountOwe() {
        return amountOwe;
    }
    public void setAmountDue(Double amountOwe){
        this.amountOwe = amountOwe;
    }
    public double getAmountGetBack(){
        return amountGetBack;
    }
    public void setAmountGetBack(double amountGetBack) {
        this.amountGetBack = amountGetBack;
    }
}
public class User{
    String userId;
    String userName; 
    UserExpenseBalanceSheet userExpenseBalanceSheet;
    public User(String id, String username){
        this.userId = id;
        this.userName = userName;
        userExpenseBalanceSheet = new UserExpenseBalanceSheet();
    }
    public String getUserId() {
        return userId;
    }
    public UserExpenseBalanceSheet getUserExpenseBalanceSheet() {
        return userExpenseBalanceSheet;
    }
}
class UserExpenseBalanceSheet{
    Map<String, Balance> userVsBalance;
    double totalYourExpense;
    double totalPayment;
    double totalYouOwe;
    double totalYouGetBack;
    public UserExpenseBalanceSheet(){
        userVsBalance = new HashMap<>();
        totalYourExpense = 0;
        totalYouOwe = 0;
        totalYouGetBack = 0;
    }
    public Map<String, Balance> getUserVsBalance(){
        return userVsBalance;
    }
    public double 
}
public class UserController{
    List<User> userList;
    public UserController(){
        userList = new ArrayList<>();
    }
    public void addUser(User user){
        userList.add(user);
    }
    public User getUser(String userId){
        for(User user: userList){
            if(user.getUserId().equals(userId)){
                return user;
            }
        }
        return null;
    }
}
class Group{
    String groupId;
    String groupName;
    List<User> groupMembers;

    List<Expense> expenseList;

    ExpenseController expenseController;
    Group(){
        groupMembers = new ArrayList<>();
        expenseList = new ArrayList<>();
        expenseController = new ExpenseController();
    }
    public void addMember(User member){
        groupMembers.add(member);
    }
    public String getGroupId(String groupId){
        this.groupId = groupId;
    }
    public void setGroupName(String groupName){
        this.groupName = groupName;
    }
}
class GroupController{
    List<Group> groupList;
    public GroupController() {
        groupList = new ArrayList<>();
    }
    public void createNewGroup(String groupId, String groupName, User createdByUser){
        Group group = new Group();
        group.setGroupId(groupId);
        group.setGroupName(groupName);

        group.addMember(createdByUser);
    }
    public Group getGroup(String groupId){
        for(Group group: groupList){
            if(group.getGroupId(groupId).equals(groupId)){
                return group;
            }
        }
        return null;
    }

}
class BalanceSheetController{
    public void updateUserExpenseBalanceSheet(User expensePaidBy, List<Split> splits, double totalExpenseAmount){
        UserExpenseBalanceSheet paidByUserExpenseSheet =  expensePaidBy.getUserExpenseBalanceSheet();
        paidByUserExpenseSheet.setTotalPayment(paidByUserExpenseSheet.getTotalPayment() + totalExpenseAmount);
        for(Split split: splits){
            User userOwe = split.getUser();
            UserExpenseBalanceSheet oweUserExpenseSheet = userOwe.getUserExpenseBalanceSheet();
            double oweAmount = split.getAmountOwe();

            Balance userOweBalance;

            if(expensePaidBy.getUserId().equals(userOwe.getUserId())){
                paidByUserExpenseSheet.setTotalYourExpense(paidByUserExpenseSheet.getTotalYourExpense() + oweAmount);
            } else {
                userOweBalance = new Balance();
                paidByUserExpenseSheet.getUserVsBalance().put(userOwe.getUserId(), userOweBalance);
            }
            userOweBalance.setAmountGetBack(userOweBalance.getAmountGetBack + oweAmount);

            oweUserExpenseSheet.setTotalYouOwe(oweUserExpenseSheet.getTotalYouOwe() + oweAmount);
            oweUserExpenseSheet.setTotalYourExpense(oweUserExpenseSheet.getTotalYourExpense() + oweAmount);

            Balance userPaidBalance;
            if(oweUserExpenseSheet.getUserVsBalance().containsKey(expensePaidBy.getUserId())){
                userPaidBalance  = oweUserExpenseSheet.getUserVsBalance().get(expensePaidBy.getUserId());
            } else {
                userPaidBalance = new Balance();
                oweUserExpenseSheet.getUserVsBalance().put(expensePaidBy.getUserId(), userPaidBalance);
            }
            userPaidBalance.setAmountDue(userPaidBalance.getAmountOwe() + oweAmount);
        }
    }
}
class Splitwise{
    UserController userController;
    GroupController groupController;

    BalanceSheetController balanceSheetController;
    public Splitwise(){
        userController = new UserController();
        groupController = new GroupController();
        balanceSheetController = new BalanceSheetController();
    }
    public void demo(){
        setupUserAndGroup();
        
        Group group = groupController.getGroup("G1001");
        group.addMember(userController.getUser("U2001"));
        group.addMember(userController.getUser("U3001"));

        List<Split> splits = new ArrayList<>();
        Split split1 = new Split(userController.getUser("U1001"), 300);
        Split split2 = new Split(userController.getUser("U2001"), 300);
        Split split3 = new Split(userController.getUser("U3001"), 300);

        splits.add(split1);
        splits.add(split2);
        splits.add(split3);

        group.createExpense("Exp1001", "Breakfast", 900, splits, ExpenseSplitType.EQUAL, userController.getUser("U1001"));

        List<Split> splits2 = new ArrayList<>();
        Split splits2_1 = new Split(userController.getUser("U1001"), 400);
        Split splits2_2 = new Split(userController.getUser("U2001"), 100);
        splits2.add(splits2_1);
        splits2.add(splits2_2);
        group.createExpense("Exp1002", "Lunch", 500, splits2, ExpenseSplitType.UNEQUAL, userController.getUser("U2001"));

        for (User user : userController.getAllUsers()) {
            balanceSheetController.showBalanceSheetOfUser(user);
        }

    }
    public void setupUserAndGroup(){
        addUsersToSplitwiseApp();
        User user1 = userController.getUser("U1001");
        groupController.createNewGroup("G1001", "Outing with friends", user1);
    }
    private void addUsersToSplitwiseApp() {

        //adding User1
        User user1 = new User("U1001", "User1");

        //adding User2
        User user2 = new User("U2001", "User2");

        //adding User3
        User user3 = new User("U3001", "User3");

        userController.addUser(user1);
        userController.addUser(user2);
        userController.addUser(user3);
    }

}