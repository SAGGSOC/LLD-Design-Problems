package musicanalytics.service;

import musicanalytics.exception.DuplicateSongException;
import musicanalytics.exception.SongNotFoundException;
import musicanalytics.model.PlayEvent;
import musicanalytics.model.Song;

import java.util.*;

/**
 * Main facade for the Music Analytics System.
 *
 * APIs:
 *   - addSong(songId)
 *   - playSong(userId, songId)
 *   - printAnalytics()                    → top songs by unique listeners
 *   - printRecentlyPlayed(userId)         → all recent unique songs
 *   - printRecentlyPlayed(userId, k)      → last K unique songs
 */
public class MusicAnalyticsService {

    private final Map<String, Song> songs;
    private final TopSongsTracker topSongsTracker;
    private final RecentlyPlayedService recentlyPlayedService;
    private final List<PlayEvent> playHistory;
    private long clock; // simple monotonic clock for ordering

    public MusicAnalyticsService() {
        this.songs = new HashMap<>();
        this.topSongsTracker = new TopSongsTracker();
        this.recentlyPlayedService = new RecentlyPlayedService();
        this.playHistory = new ArrayList<>();
        this.clock = 0;
    }

    // ═══════════════════════════════════════════════
    // API: add_song(songId)
    // ═══════════════════════════════════════════════
    public void addSong(String songId) {
        if (songId == null || songId.isEmpty()) {
            throw new IllegalArgumentException("Song ID cannot be null or empty");
        }
        if (songs.containsKey(songId)) {
            throw new DuplicateSongException(songId);
        }
        Song song = new Song(songId, songId);
        songs.put(songId, song);
        topSongsTracker.addSong(song);
    }

    // ═══════════════════════════════════════════════
    // API: play_song(userId, songId)
    // ═══════════════════════════════════════════════
    public void playSong(String userId, String songId) {
        if (!songs.containsKey(songId)) {
            throw new SongNotFoundException(songId);
        }

        Song song = songs.get(songId);

        // Update top songs leaderboard (unique listeners)
        topSongsTracker.recordPlay(userId, song);

        // Update recently played for user
        recentlyPlayedService.recordPlay(userId, songId);

        // Record event
        playHistory.add(new PlayEvent(userId, songId, clock++));
    }

    // ═══════════════════════════════════════════════
    // API: print_analytics()
    // Most played songs ranked by unique user count
    // ═══════════════════════════════════════════════
    public void printAnalytics() {
        System.out.println("─── Most Played Songs (by unique listeners) ───");
        List<Song> top = topSongsTracker.getAll();
        int rank = 1;
        for (Song song : top) {
            System.out.printf("  %d. %s — %d unique listeners%n",
                rank++, song.getSongId(), song.getUniqueListenerCount());
        }
        if (top.isEmpty()) {
            System.out.println("  (no plays yet)");
        }
    }

    // ═══════════════════════════════════════════════
    // API: print_recently_played(userId)
    // All recently played unique songs, most recent first
    // ═══════════════════════════════════════════════
    public void printRecentlyPlayed(String userId) {
        System.out.println("─── Recently Played (user: " + userId + ") ───");
        List<String> recent = recentlyPlayedService.getRecentlyPlayed(userId);
        if (recent.isEmpty()) {
            System.out.println("  (no plays yet)");
            return;
        }
        for (int i = 0; i < recent.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, recent.get(i));
        }
    }

    // ═══════════════════════════════════════════════
    // API: print_recently_played(userId, k)
    // Last K unique songs, most recent first
    // ═══════════════════════════════════════════════
    public void printRecentlyPlayed(String userId, int k) {
        System.out.println("─── Recently Played (user: " + userId + ", top " + k + ") ───");
        List<String> recent = recentlyPlayedService.getRecentlyPlayed(userId, k);
        if (recent.isEmpty()) {
            System.out.println("  (no plays yet)");
            return;
        }
        for (int i = 0; i < recent.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, recent.get(i));
        }
    }

    // ═══════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════
    public Song getSong(String songId) {
        if (!songs.containsKey(songId)) throw new SongNotFoundException(songId);
        return songs.get(songId);
    }

    public List<PlayEvent> getPlayHistory() {
        return Collections.unmodifiableList(playHistory);
    }
}
