package instagram.service;

import instagram.exception.UserNotFoundException;
import instagram.model.User;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class UserService {
    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, String> userIdByUsername = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    public User registerUser(String username, String bio, String avatarUrl) {
        if (userIdByUsername.containsKey(username)) {
            throw new IllegalArgumentException("Username taken: " + username);
        }
        String userId = "U-" + idCounter.getAndIncrement();
        User user = new User(userId, username, bio, avatarUrl);
        usersById.put(userId, user);
        userIdByUsername.put(username, userId);
        return user;
    }

    public User getUser(String userId) {
        User user = usersById.get(userId);
        if (user == null) throw new UserNotFoundException(userId);
        return user;
    }

    public User getUserByUsername(String username) {
        String userId = userIdByUsername.get(username);
        if (userId == null) throw new UserNotFoundException(username);
        return getUser(userId);
    }

    public boolean exists(String userId) {
        return usersById.containsKey(userId);
    }
}
