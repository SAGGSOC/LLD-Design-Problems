package musicanalytics.model;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a song in the system.
 * Tracks the set of unique users who have played it.
 */
public class Song {
    private final String songId;
    private final String title;
    private final Set<String> uniqueListeners; // set of userIds

    public Song(String songId, String title) {
        this.songId = songId;
        this.title = title;
        this.uniqueListeners = new HashSet<>();
    }

    public String getSongId() { return songId; }
    public String getTitle() { return title; }

    public void addListener(String userId) {
        uniqueListeners.add(userId);
    }

    public int getUniqueListenerCount() {
        return uniqueListeners.size();
    }

    public Set<String> getUniqueListeners() {
        return uniqueListeners;
    }
}
