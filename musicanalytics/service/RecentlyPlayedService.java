package musicanalytics.service;

import musicanalytics.model.User;

import java.util.*;

/**
 * Manages recently played unique songs per user.
 *
 * Each user maintains a bounded LinkedHashSet of song IDs.
 * - Replaying a song moves it to the most recent position.
 * - Oldest entries are evicted when capacity is exceeded.
 *
 * Time complexity:
 *   - recordPlay: O(1)
 *   - getRecentlyPlayed: O(K)
 */
public class RecentlyPlayedService {

    private final Map<String, User> users;

    public RecentlyPlayedService() {
        this.users = new HashMap<>();
    }

    /**
     * Record a play event for a user.
     * Auto-registers the user if not seen before.
     */
    public void recordPlay(String userId, String songId) {
        User user = users.computeIfAbsent(userId, User::new);
        user.playSong(songId);
    }

    /**
     * Get all recently played unique songs for a user (most recent first).
     */
    public List<String> getRecentlyPlayed(String userId) {
        User user = users.get(userId);
        if (user == null) return Collections.emptyList();

        // LinkedHashSet is insertion-ordered (oldest first), we want most recent first
        LinkedList<String> result = new LinkedList<>();
        for (String songId : user.getRecentlyPlayed()) {
            result.addFirst(songId); // reverse to get most recent first
        }
        return result;
    }

    /**
     * Get the K most recently played unique songs for a user.
     */
    public List<String> getRecentlyPlayed(String userId, int k) {
        List<String> all = getRecentlyPlayed(userId);
        return all.subList(0, Math.min(k, all.size()));
    }

    public User getUser(String userId) {
        return users.get(userId);
    }
}
