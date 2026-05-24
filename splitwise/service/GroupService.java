package splitwise.service;

import splitwise.model.Group;
import splitwise.model.User;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GroupService {
    private final Map<String, Group> groupsById = new ConcurrentHashMap<>();

    public Group createGroup(String name, User createdBy) {
        String groupId = "GRP-" + (groupsById.size() + 1);
        Group group = new Group(groupId, name, createdBy);
        groupsById.put(groupId, group);
        return group;
    }

    public Group getGroup(String groupId) {
        Group group = groupsById.get(groupId);
        if (group == null) throw new IllegalArgumentException("Group not found: " + groupId);
        return group;
    }

    public void addMember(String groupId, User user) {
        getGroup(groupId).addMember(user);
    }

    public void removeMember(String groupId, User user) {
        getGroup(groupId).removeMember(user);
    }
}
