package musicanalytics.service;

import musicanalytics.model.Song;

import java.util.*;

/**
 * Tracks the most played songs ranked by unique listener count.
 *
 * Uses a TreeSet with a custom comparator to maintain sorted order.
 * When a song's listener count changes, we remove and re-insert to maintain ordering.
 *
 * Time complexity:
 *   - recordPlay: O(log N)
 *   - getTopK: O(K)
 */
public class TopSongsTracker {

    // Sorted by unique listener count descending, then songId for tie-breaking
    private final TreeSet<Song> leaderboard;
    private final Map<String, Song> songMap;

    public TopSongsTracker() {
        this.leaderboard = new TreeSet<>((a, b) -> {
            int cmp = Integer.compare(b.getUniqueListenerCount(), a.getUniqueListenerCount());
            if (cmp != 0) return cmp;
            return a.getSongId().compareTo(b.getSongId()); // stable tie-break
        });
        this.songMap = new HashMap<>();
    }

    /**
     * Record that a user played a song.
     * Updates the leaderboard if the unique listener count changes.
     */
    public void recordPlay(String userId, Song song) {
        boolean isNewListener = !song.getUniqueListeners().contains(userId);

        if (isNewListener) {
            // Remove before updating count (TreeSet ordering depends on count)
            leaderboard.remove(song);
            song.addListener(userId);
            leaderboard.add(song);
        }
        // If not a new listener, unique count doesn't change — no reordering needed
    }

    public void addSong(Song song) {
        songMap.put(song.getSongId(), song);
        leaderboard.add(song);
    }

    /**
     * Get top K songs by unique listener count.
     */
    public List<Song> getTopK(int k) {
        List<Song> result = new ArrayList<>();
        int count = 0;
        for (Song song : leaderboard) {
            if (count >= k) break;
            if (song.getUniqueListenerCount() > 0) {
                result.add(song);
                count++;
            }
        }
        return result;
    }

    /**
     * Get all songs sorted by unique listeners descending.
     */
    public List<Song> getAll() {
        List<Song> result = new ArrayList<>();
        for (Song song : leaderboard) {
            if (song.getUniqueListenerCount() > 0) {
                result.add(song);
            }
        }
        return result;
    }
}
