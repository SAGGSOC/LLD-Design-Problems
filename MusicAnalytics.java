import java.util.*;
import java.util.stream.Collectors;

/**
 * Music Player Analytics — interview-ready, single file.
 *
 * Part 1 (base): playSong, addSong, printAnalytics (most played song)
 * Part 2 (follow-up): starSong / unstarSong per user, lastNFavoritePlayed
 *
 * Data-structure thinking (key to a strong interview answer):
 *
 *   Global play counts:
 *     Map<songId, playCount>            — O(1) increment on playSong
 *     For most-played: sort on print, or maintain a TreeSet by count (trade-off).
 *
 *   Per-user starred set:
 *     Map<userId, Set<songId>>          — O(1) star/unstar/check
 *
 *   Per-user last-N favorites played:
 *     Map<userId, Deque<songId>>         — when a user plays a STARRED song,
 *                                          remove existing occurrence and push to front.
 *     → "last N favorite played" = take first N from the deque.
 *     → Deduplication: if user replays same starred song, move it to front.
 *
 * The key insight that trips people up: "favorite songs played" means played AND starred.
 * So the deque only gets updated if the song is starred at play time.
 */
public class MusicAnalytics {

    // ─── Models ───

    static class Song {
        final String songId;
        final String title;
        Song(String songId, String title) { this.songId = songId; this.title = title; }
    }

    // ─── Service ───

    static class MusicService {

        // Song catalog
        private final Map<String, Song> songs = new HashMap<>();

        // Global play counts — key to "most played song"
        private final Map<String, Integer> playCounts = new HashMap<>();

        // Per-user starred songs — O(1) star/unstar/check
        private final Map<String, Set<String>> starredByUser = new HashMap<>();

        // Per-user favorite-play history — LinkedList so we can move-to-front in O(1)
        // via iterator removal + addFirst. LinkedHashSet would ALSO work if we don't
        // need strict list semantics (it preserves insertion order + allows remove).
        private final Map<String, LinkedHashSet<String>> favoritePlayedByUser = new HashMap<>();

        /** Part 1: register a song in the catalog. */
        public void addSong(String songId, String title) {
            if (songs.containsKey(songId)) {
                throw new IllegalArgumentException("Song already exists: " + songId);
            }
            songs.put(songId, new Song(songId, title));
            playCounts.put(songId, 0);
        }

        /** Part 1: record a play. Also updates favorite-play history if starred. */
        public void playSong(String songId, String userId) {
            if (!songs.containsKey(songId)) {
                throw new IllegalArgumentException("Unknown song: " + songId);
            }

            // Global count for "most played"
            playCounts.merge(songId, 1, Integer::sum);

            // Part 2 integration: if the user has this starred, update their favorite history
            Set<String> userStarred = starredByUser.getOrDefault(userId, Collections.emptySet());
            if (userStarred.contains(songId)) {
                LinkedHashSet<String> history = favoritePlayedByUser
                    .computeIfAbsent(userId, k -> new LinkedHashSet<>());
                // Move-to-front: if already in history, remove first so re-insert puts at end
                history.remove(songId);
                history.add(songId);
            }
        }

        /** Part 1: print analytics — shows top-5 most played. */
        public void printAnalytics() {
            System.out.println("=== Analytics ===");
            System.out.println("Total plays: "
                + playCounts.values().stream().mapToInt(Integer::intValue).sum());

            List<Map.Entry<String, Integer>> ranked = playCounts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toList());

            System.out.println("Top songs:");
            int rank = 1;
            for (Map.Entry<String, Integer> entry : ranked) {
                Song song = songs.get(entry.getKey());
                System.out.println("  " + rank++ + ". " + song.title
                    + " — " + entry.getValue() + " plays");
            }

            if (!ranked.isEmpty()) {
                Song topSong = songs.get(ranked.get(0).getKey());
                System.out.println("Most played: " + topSong.title);
            }
        }

        // ─── Part 2: star/unstar and favorite history ───

        /** Star a song for a user. Idempotent. Returns true if newly starred. */
        public boolean starSong(String userId, String songId) {
            if (!songs.containsKey(songId)) {
                throw new IllegalArgumentException("Unknown song: " + songId);
            }
            return starredByUser.computeIfAbsent(userId, k -> new HashSet<>())
                .add(songId);
        }

        /** Unstar a song for a user. Also removes from favorite-play history. */
        public boolean unstarSong(String userId, String songId) {
            Set<String> starred = starredByUser.get(userId);
            if (starred == null) return false;
            boolean wasStarred = starred.remove(songId);

            // Clean up favorite history — if it's no longer starred, shouldn't appear
            if (wasStarred) {
                LinkedHashSet<String> history = favoritePlayedByUser.get(userId);
                if (history != null) history.remove(songId);
            }
            return wasStarred;
        }

        public boolean isStarred(String userId, String songId) {
            Set<String> starred = starredByUser.get(userId);
            return starred != null && starred.contains(songId);
        }

        public Set<String> getStarred(String userId) {
            return Collections.unmodifiableSet(
                starredByUser.getOrDefault(userId, Collections.emptySet()));
        }

        /**
         * Last N starred songs played by this user, most recent first.
         * Only songs currently STARRED are returned (if user unstarred after playing,
         * we already cleaned it out via unstarSong).
         */
        public List<Song> getLastNFavoritePlayed(String userId, int n) {
            LinkedHashSet<String> history = favoritePlayedByUser.get(userId);
            if (history == null || history.isEmpty()) return Collections.emptyList();

            // LinkedHashSet is FIFO — last inserted is LAST in iteration.
            // We want most-recent first, so iterate in reverse.
            List<String> all = new ArrayList<>(history);
            Collections.reverse(all);

            return all.stream()
                .limit(n)
                .map(songs::get)
                .collect(Collectors.toList());
        }
    }

    // ─── Demo ───

    public static void main(String[] args) {
        MusicService service = new MusicService();

        // ─── Part 1: base analytics ───
        System.out.println("--- Part 1: base analytics ---");
        service.addSong("S1", "Blinding Lights");
        service.addSong("S2", "Shape of You");
        service.addSong("S3", "Bohemian Rhapsody");
        service.addSong("S4", "Hotel California");

        // Multiple users playing
        service.playSong("S1", "u1"); service.playSong("S1", "u1"); service.playSong("S1", "u2");
        service.playSong("S2", "u1"); service.playSong("S2", "u2");
        service.playSong("S3", "u3"); service.playSong("S3", "u3"); service.playSong("S3", "u3");
        service.playSong("S3", "u1");
        service.playSong("S4", "u2");

        service.printAnalytics();

        // ─── Part 2: starring ───
        System.out.println("\n--- Part 2: star/unstar ---");
        service.starSong("u1", "S1");
        service.starSong("u1", "S2");
        service.starSong("u1", "S3");
        System.out.println("u1's starred: " + service.getStarred("u1"));

        // Play some starred songs in specific order
        // After user stars, play order matters for "last N favorite played"
        service.playSong("S1", "u1");  // starred → goes to history
        service.playSong("S2", "u1");  // starred → goes to history
        service.playSong("S4", "u1");  // NOT starred → ignored in history
        service.playSong("S3", "u1");  // starred → goes to history

        List<Song> last3 = service.getLastNFavoritePlayed("u1", 3);
        System.out.println("\nu1's last 3 favorite played (most recent first):");
        for (Song s : last3) System.out.println("  - " + s.title);

        // Replay an older favorite — should move to front
        System.out.println("\nu1 replays S1 (was oldest in history):");
        service.playSong("S1", "u1");
        List<Song> last3Again = service.getLastNFavoritePlayed("u1", 3);
        for (Song s : last3Again) System.out.println("  - " + s.title);

        // Unstar removes from history
        System.out.println("\nu1 unstars S1:");
        service.unstarSong("u1", "S1");
        List<Song> afterUnstar = service.getLastNFavoritePlayed("u1", 5);
        for (Song s : afterUnstar) System.out.println("  - " + s.title);

        // Ask for more than available
        System.out.println("\nu1's last 10 favorite played (only 2 exist):");
        for (Song s : service.getLastNFavoritePlayed("u1", 10)) {
            System.out.println("  - " + s.title);
        }

        // Another user with no starred songs
        System.out.println("\nu2's last 5 favorite (none starred): "
            + service.getLastNFavoritePlayed("u2", 5));
    }
}
