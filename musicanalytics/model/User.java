package musicanalytics.model;

import java.util.LinkedHashSet;

/**
 * Represents a user in the music system.
 * Tracks recently played unique songs using a LinkedHashSet
 * (insertion-ordered, deduped).
 *
 * When a song is replayed, it moves to the most recent position.
 */
public class User {
    private final String userId;
    private final LinkedHashSet<String> recentlyPlayed; // songIds, insertion order = play order
    private final int maxRecent;

    private static final int DEFAULT_MAX_RECENT = 50;

    public User(String userId) {
        this(userId, DEFAULT_MAX_RECENT);
    }

    public User(String userId, int maxRecent) {
        this.userId = userId;
        this.maxRecent = maxRecent;
        this.recentlyPlayed = new LinkedHashSet<>();
    }

    public String getUserId() { return userId; }

    /**
     * Record a song play. If already in recent list, move it to the end (most recent).
     * If list exceeds capacity, evict the oldest entry.
     */
    public void playSong(String songId) {
        // Remove first so re-insertion puts it at the end (most recent)
        recentlyPlayed.remove(songId);
        recentlyPlayed.add(songId);

        // Evict oldest if over capacity
        if (recentlyPlayed.size() > maxRecent) {
            String oldest = recentlyPlayed.iterator().next();
            recentlyPlayed.remove(oldest);
        }
    }

    /**
     * Get recently played unique songs, most recent first.
     */
    public LinkedHashSet<String> getRecentlyPlayed() {
        return recentlyPlayed;
    }

    public int getMaxRecent() { return maxRecent; }
}
