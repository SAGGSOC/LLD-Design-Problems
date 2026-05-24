package splitwise.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Group {
    private final String groupId;
    private final String name;
    private final User createdBy;
    private final Set<User> members;
    private final List<String> expenseIds;
    private final Instant createdAt;

    public Group(String groupId, String name, User createdBy) {
        this.groupId = groupId;
        this.name = name;
        this.createdBy = createdBy;
        this.members = new HashSet<>();
        this.members.add(createdBy);
        this.expenseIds = new ArrayList<>();
        this.createdAt = Instant.now();
    }

    public void addMember(User user)           { members.add(user); }
    public void removeMember(User user)        { members.remove(user); }
    public void addExpense(String expenseId)   { expenseIds.add(expenseId); }

    public boolean hasMember(User user)        { return members.contains(user); }

    public String getGroupId()        { return groupId; }
    public String getName()           { return name; }
    public User getCreatedBy()        { return createdBy; }
    public Set<User> getMembers()     { return members; }
    public List<String> getExpenseIds() { return expenseIds; }
    public Instant getCreatedAt()     { return createdAt; }
}
