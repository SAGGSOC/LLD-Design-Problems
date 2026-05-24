package cricinfo;

import cricinfo.enums.ExtraType;
import cricinfo.enums.MatchFormat;
import cricinfo.enums.WicketType;
import cricinfo.model.*;
import cricinfo.observer.ConsoleCommentaryObserver;

import java.util.Arrays;
import java.util.List;

public class CricinfoDemo {

    public static void main(String[] args) {
        // ─── Setup teams (only 4 players each for a condensed demo) ───
        Player virat = new Player("P-1", "Virat Kohli", "India", "batsman");
        Player rohit = new Player("P-2", "Rohit Sharma", "India", "batsman");
        Player dhoni = new Player("P-3", "MS Dhoni", "India", "wicket-keeper");
        Player bumrah = new Player("P-4", "Jasprit Bumrah", "India", "bowler");
        List<Player> indiaPlayers = Arrays.asList(virat, rohit, dhoni, bumrah);
        Team india = new Team("T-IND", "India", "India", indiaPlayers, virat);

        Player smith = new Player("P-5", "Steve Smith", "Australia", "batsman");
        Player warner = new Player("P-6", "David Warner", "Australia", "batsman");
        Player maxwell = new Player("P-7", "Glenn Maxwell", "Australia", "all-rounder");
        Player starc = new Player("P-8", "Mitchell Starc", "Australia", "bowler");
        List<Player> aussiePlayers = Arrays.asList(smith, warner, maxwell, starc);
        Team australia = new Team("T-AUS", "Australia", "Australia", aussiePlayers, smith);

        // ─── Create match (T20, 1 over for brevity — maxOvers overridden to be small) ───
        // Using T20 rules but we'll just play a few balls to demonstrate
        Match match = new Match("M-001", india, australia, MatchFormat.T20, "MCG");
        match.subscribe(new ConsoleCommentaryObserver());

        System.out.println("╔═══════════════════════════════════════╗");
        System.out.println("║  " + match.getTeamA() + " vs " + match.getTeamB() + " — " + match.getFormat() + " @ MCG");
        System.out.println("╚═══════════════════════════════════════╝\n");

        // ─── Innings 1: India batting ───
        System.out.println("=== INNINGS 1: " + india.getName() + " batting ===\n");
        Innings innings1 = match.startInnings(india, australia);
        innings1.openBatsmen(rohit, virat);
        innings1.setBowler(starc);

        // Over 1 (6 legal balls)
        match.recordBall(ball(0, 1, starc, rohit, virat).runs(4).build());
        match.recordBall(ball(0, 2, starc, rohit, virat).runs(1).build());
        // After 1 run, strike rotates: now virat on strike
        match.recordBall(ball(0, 3, starc, virat, rohit).runs(6).build());
        match.recordBall(ball(0, 4, starc, virat, rohit).runs(0).build());
        match.recordBall(ball(0, 5, starc, virat, rohit).runs(2).build());
        match.recordBall(ball(0, 6, starc, virat, rohit).runs(0).build());
        // End of over 1 — strike rotates, bowler must be set again

        innings1.setBowler(maxwell);
        // Over 2
        match.recordBall(ball(1, 1, maxwell, rohit, virat).runs(1).build());
        // Strike to virat
        match.recordBall(ball(1, 2, maxwell, virat, rohit)
            .extra(ExtraType.WIDE, 1).build());  // wide, not a legal delivery
        match.recordBall(ball(1, 2, maxwell, virat, rohit)
            .wicketWithFielder(WicketType.CAUGHT, virat, smith).build());  // OUT!

        // New batsman comes in
        innings1.newBatsman(dhoni);
        match.recordBall(ball(1, 3, maxwell, dhoni, rohit).runs(4).build());
        match.recordBall(ball(1, 4, maxwell, dhoni, rohit).runs(0).build());
        match.recordBall(ball(1, 5, maxwell, dhoni, rohit).runs(6).build());
        match.recordBall(ball(1, 6, maxwell, dhoni, rohit).runs(1).build());
        // End of over 2

        // Print current state
        System.out.println("\n--- End of over 2 ---");
        System.out.println("India: " + innings1.getScoreDisplay()
            + " | Run rate: " + String.format("%.2f", innings1.getRunRate()));
        System.out.println("\nBatting card:");
        for (BattingStats bs : innings1.getAllBattingStats()) {
            if (bs.getBallsFaced() > 0) {
                System.out.println("  " + bs);
            }
        }
        System.out.println("\nBowling card:");
        for (BowlingStats bowlerStats : innings1.getAllBowlingStats()) {
            if (bowlerStats.getRunsConceded() > 0 || bowlerStats.getWickets() > 0) {
                System.out.println("  " + bowlerStats);
            }
        }

        // Close innings 1 manually for demo (we only played 2 overs, T20 is 20)
        innings1.closeInnings();
        int targetRuns = innings1.getTotalRuns() + 1;
        System.out.println("\nIndia finish with " + innings1.getScoreDisplay());
        System.out.println("Australia need " + targetRuns + " to win.\n");

        // ─── Innings 2: Australia batting ───
        System.out.println("\n=== INNINGS 2: " + australia.getName() + " chasing "
            + targetRuns + " ===\n");
        Innings innings2 = match.startInnings(australia, india);
        innings2.openBatsmen(warner, smith);
        innings2.setBowler(bumrah);

        // Australia goes all out fast
        match.recordBall(ball(0, 1, bumrah, warner, smith).runs(4).build());
        match.recordBall(ball(0, 2, bumrah, warner, smith).runs(6).build());
        match.recordBall(ball(0, 3, bumrah, warner, smith).runs(1).build());
        // strike to smith
        match.recordBall(ball(0, 4, bumrah, smith, warner).runs(4).build());
        match.recordBall(ball(0, 5, bumrah, smith, warner).runs(4).build());
        match.recordBall(ball(0, 6, bumrah, smith, warner).runs(1).build());
        // over 1 done, strike to warner

        innings2.setBowler(rohit);  // part-time bowler
        match.recordBall(ball(1, 1, rohit, warner, smith).runs(6).build());
        match.recordBall(ball(1, 2, rohit, warner, smith).runs(6).build());
        // Target should be crossed — match ends here via isMatchComplete check

        // ─── Final state ───
        System.out.println("\n=== Final scorecard ===");
        System.out.println("India: " + innings1.getScoreDisplay());
        System.out.println("Australia: " + innings2.getScoreDisplay());
        System.out.println("Match status: " + match.getStatus());
        System.out.println("Result: " + match.getResult());
    }

    private static Ball.Builder ball(int overNum, int ballInOver, Player bowler,
                                      Player striker, Player nonStriker) {
        return Ball.builder()
            .ball(overNum, ballInOver)
            .bowler(bowler)
            .striker(striker)
            .nonStriker(nonStriker);
    }
}
