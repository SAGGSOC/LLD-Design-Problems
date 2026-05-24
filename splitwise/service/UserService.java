package splitwise.service;

import splitwise.model.User;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserService {
    private final Map<String, User> usersById = new ConcurrentHashMap<>();

    public User createUser(String name, String email, String phone) {
        String userId = "USR-" + (usersById.size() + 1);
        User user = new User(userId, name, email, phone);
        usersById.put(userId, user);
        return user;
    }

    public User getUser(String userId) {
        User user = usersById.get(userId);
        if (user == null) throw new IllegalArgumentException("User not found: " + userId);
        return user;
    }

    public boolean exists(String userId) {
        return usersById.containsKey(userId);
    }
}
