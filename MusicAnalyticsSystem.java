import java.util.*;

/*
 * Music Streaming Analytics System
 *
 * Features:
 * 1. Track song plays
 * 2. Return songs by unique users count
 * 3. Return per-user recently played top K songs
 * 4. Optimized O(1) recent song updates
 *
 * Core Entities:
 * - User
 * - Song
 * - SongPlayEvent
 * - MusicAnalyticsSystem
 */

public class MusicAnalyticsSystem {

    /*
     * =====================================
     * CORE ENTITIES
     * =====================================
     */

    static class User {

        private final String userId;

        public User(String userId) {
            this.userId = userId;
        }

        public String getUserId() {
            return userId;
        }

        @Override
        public String toString() {
            return userId;
        }
    }

    static class Song {

        private final String songId;
        private final String songName;

        public Song(String songId,
                    String songName) {

            this.songId = songId;
            this.songName = songName;
        }

        public String getSongId() {
            return songId;
        }

        public String getSongName() {
            return songName;
        }

        @Override
        public String toString() {
            return songName + "(" + songId + ")";
        }
    }

    static class SongPlayEvent {

        private final User user;
        private final Song song;
        private final long timestamp;

        public SongPlayEvent(User user,
                             Song song,
                             long timestamp) {

            this.user = user;
            this.song = song;
            this.timestamp = timestamp;
        }

        public User getUser() {
            return user;
        }

        public Song getSong() {
            return song;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /*
     * =====================================
     * DLL NODE
     * =====================================
     */

    static class Node {

        Song song;

        Node prev;
        Node next;

        public Node(Song song) {
            this.song = song;
        }
    }

    /*
     * =====================================
     * RECENT SONGS LIST
     * =====================================
     *
     * O(1):
     * - add recent song
     * - remove existing song
     * - evict oldest
     */

    static class RecentSongsList {

        /*
         * songId -> node
         */
        private final Map<String, Node> songToNode;

        private final Node head;
        private final Node tail;

        private final int capacity;

        private int size;

        public RecentSongsList(int capacity) {

            this.capacity = capacity;

            this.songToNode = new HashMap<>();

            this.head = new Node(null);
            this.tail = new Node(null);

            head.next = tail;
            tail.prev = head;

            this.size = 0;
        }

        /*
         * Add song as most recent
         */
        public void playSong(Song song) {

            /*
             * Remove existing occurrence
             */
            if (songToNode.containsKey(song.getSongId())) {

                Node existing =
                        songToNode.get(song.getSongId());

                remove(existing);
            }

            /*
             * Insert at front
             */
            Node node = new Node(song);

            addToFront(node);

            songToNode.put(song.getSongId(), node);

            size++;

            /*
             * Evict oldest
             */
            if (size > capacity) {

                Node lru = tail.prev;

                remove(lru);

                songToNode.remove(
                        lru.song.getSongId()
                );

                size--;
            }
        }

        private void addToFront(Node node) {

            node.next = head.next;
            node.prev = head;

            head.next.prev = node;
            head.next = node;
        }

        private void remove(Node node) {

            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        public List<Song> getRecentSongs() {

            List<Song> result =
                    new ArrayList<>();

            Node curr = head.next;

            while (curr != tail) {

                result.add(curr.song);

                curr = curr.next;
            }

            return result;
        }
    }

    /*
     * =====================================
     * MAIN ANALYTICS SYSTEM
     * =====================================
     */

    static class AnalyticsService {

        /*
         * songId -> unique users
         */
        private final Map<String, Set<String>>
                songUniqueUsers;

        /*
         * userId -> recent songs
         */
        private final Map<String, RecentSongsList>
                userRecentSongs;

        /*
         * Store events
         */
        private final List<SongPlayEvent>
                playHistory;

        private final int recentSongsLimit;

        public AnalyticsService(
                int recentSongsLimit) {

            this.recentSongsLimit =
                    recentSongsLimit;

            this.songUniqueUsers =
                    new HashMap<>();

            this.userRecentSongs =
                    new HashMap<>();

            this.playHistory =
                    new ArrayList<>();
        }

        /*
         * User plays a song
         */
        public void playSong(User user,
                             Song song,
                             long timestamp) {

            /*
             * Store event
             */
            SongPlayEvent event =
                    new SongPlayEvent(
                            user,
                            song,
                            timestamp
                    );

            playHistory.add(event);

            /*
             * Update unique users count
             */
            songUniqueUsers
                    .computeIfAbsent(
                            song.getSongId(),
                            x -> new HashSet<>()
                    )
                    .add(user.getUserId());

            /*
             * Update recent songs
             */
            userRecentSongs
                    .computeIfAbsent(
                            user.getUserId(),
                            x -> new RecentSongsList(
                                    recentSongsLimit
                            )
                    )
                    .playSong(song);
        }

        /*
         * Return songs ordered by
         * most unique users
         */
        public List<String>
        getSongsByUniqueUsers() {

            List<String> songs =
                    new ArrayList<>(
                            songUniqueUsers.keySet()
                    );

            songs.sort((a, b) ->
                    Integer.compare(
                            songUniqueUsers.get(b).size(),
                            songUniqueUsers.get(a).size()
                    )
            );

            return songs;
        }

        /*
         * Return recent songs for user
         */
        public List<Song>
        getRecentSongs(String userId) {

            if (!userRecentSongs.containsKey(
                    userId
            )) {

                return new ArrayList<>();
            }

            return userRecentSongs
                    .get(userId)
                    .getRecentSongs();
        }

        /*
         * Utility
         */
        public void printUniqueUserCounts() {

            System.out.println(
                    "\n===== UNIQUE USER COUNTS ====="
            );

            for (String songId :
                    songUniqueUsers.keySet()) {

                System.out.println(
                        songId +
                                " -> " +
                                songUniqueUsers
                                        .get(songId)
                                        .size()
                );
            }
        }
    }

    /*
     * =====================================
     * DRIVER
     * =====================================
     */

    public static void main(String[] args) {

        AnalyticsService analytics =
                new AnalyticsService(3);

        /*
         * Users
         */
        User u1 = new User("U1");
        User u2 = new User("U2");
        User u3 = new User("U3");

        /*
         * Songs
         */
        Song s1 = new Song("S1", "Believer");
        Song s2 = new Song("S2", "ShapeOfYou");
        Song s3 = new Song("S3", "Starboy");
        Song s4 = new Song("S4", "BlindingLights");

        /*
         * Play events
         */
        analytics.playSong(u1, s1, 1);
        analytics.playSong(u2, s1, 2);
        analytics.playSong(u1, s2, 3);
        analytics.playSong(u3, s2, 4);
        analytics.playSong(u1, s3, 5);
        analytics.playSong(u1, s2, 6);
        analytics.playSong(u1, s4, 7);

        /*
         * Unique users
         */
        analytics.printUniqueUserCounts();

        /*
         * Songs ordered by unique users
         */
        System.out.println(
                "\n===== SONGS BY UNIQUE USERS ====="
        );

        System.out.println(
                analytics.getSongsByUniqueUsers()
        );

        /*
         * Recent songs
         */
        System.out.println(
                "\n===== RECENT SONGS FOR U1 ====="
        );

        System.out.println(
                analytics.getRecentSongs("U1")
        );
    }
}