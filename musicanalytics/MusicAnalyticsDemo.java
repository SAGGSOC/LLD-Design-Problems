package musicanalytics;

import musicanalytics.service.MusicAnalyticsService;

public class MusicAnalyticsDemo {

    public static void main(String[] args) {
        MusicAnalyticsService service = new MusicAnalyticsService();

        // Add songs
        service.addSong("S1");
        service.addSong("S2");
        service.addSong("S3");
        service.addSong("S4");
        service.addSong("S5");

        // ═══════════════════════════════════════════════
        // Simulate plays
        // ═══════════════════════════════════════════════
        // S1: played by U1, U2, U3 → 3 unique listeners
        service.playSong("U1", "S1");
        service.playSong("U2", "S1");
        service.playSong("U3", "S1");

        // S2: played by U1, U2 → 2 unique listeners
        service.playSong("U1", "S2");
        service.playSong("U2", "S2");

        // S3: played by U1 only → 1 unique listener
        service.playSong("U1", "S3");

        // S4: played by U1, U2, U3, U4 → 4 unique listeners
        service.playSong("U1", "S4");
        service.playSong("U2", "S4");
        service.playSong("U3", "S4");
        service.playSong("U4", "S4");

        // U1 replays S1 (should NOT increase unique count)
        service.playSong("U1", "S1");

        // S5: played by U1 → 1 unique listener
        service.playSong("U1", "S5");

        // ═══════════════════════════════════════════════
        // PART 1: Most Played Songs by Unique Users
        // ═══════════════════════════════════════════════
        System.out.println("═══ PART 1: Analytics ═══\n");
        service.printAnalytics();
        // Expected order: S4(4), S1(3), S2(2), S3(1), S5(1)

        // ═══════════════════════════════════════════════
        // PART 2: Recently Played Unique Songs
        // ═══════════════════════════════════════════════
        System.out.println("\n═══ PART 2: Recently Played ═══\n");

        // U1's play order: S1, S2, S3, S4, S1(replay→moves to end), S5
        // Unique recent (most recent first): S5, S1, S4, S3, S2
        service.printRecentlyPlayed("U1");

        System.out.println();

        // Top 3 recently played for U1
        service.printRecentlyPlayed("U1", 3);

        System.out.println();

        // U3's play order: S1, S4
        // Unique recent (most recent first): S4, S1
        service.printRecentlyPlayed("U3");

        System.out.println();

        // U4 only played S4
        service.printRecentlyPlayed("U4");
    }
}
