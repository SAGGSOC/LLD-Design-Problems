package cricinfo.model;

import cricinfo.enums.BattingStatus;
import cricinfo.enums.ExtraType;
import cricinfo.exception.InvalidBallException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One team batting while the other bowls. The core of live match state.
 *
 * Owns:
 *   - Ordered list of balls (source of truth)
 *   - Cached stats per player (batting + bowling)
 *   - Current strike/non-strike/bowler
 *
 * When a ball is recorded, this class updates everything consistently.
 */
public class Innings {
    private final int inningsNumber;
    private final Team battingTeam;
    private final Team bowlingTeam;
    private final int maxOvers;   // 20 for T20, 50 for ODI, -1 for unlimited (TEST)

    private final List<Ball> balls = new ArrayList<>();
    private final Map<String, BattingStats> battingStats = new ConcurrentHashMap<>();
    private final Map<String, BowlingStats> bowlingStats = new ConcurrentHashMap<>();

    private int totalRuns;
    private int totalWickets;
    private int totalExtras;
    private int legalDeliveries;  // valid balls bowled this innings

    private Player currentStriker;
    private Player currentNonStriker;
    private Player currentBowler;

    private int runsInCurrentOver;
    private int legalBallsInCurrentOver;

    private boolean inningsEnded = false;

    public Innings(int inningsNumber, Team battingTeam, Team bowlingTeam, int maxOvers) {
        this.inningsNumber = inningsNumber;
        this.battingTeam = battingTeam;
        this.bowlingTeam = bowlingTeam;
        this.maxOvers = maxOvers;

        // Pre-populate BattingStats for every player (YET_TO_BAT)
        for (Player p : battingTeam.getPlayers()) {
            battingStats.put(p.getPlayerId(), new BattingStats(p));
        }
        for (Player p : bowlingTeam.getPlayers()) {
            bowlingStats.put(p.getPlayerId(), new BowlingStats(p));
        }
    }

    public void openBatsmen(Player striker, Player nonStriker) {
        if (!battingTeam.getPlayers().contains(striker)
                || !battingTeam.getPlayers().contains(nonStriker)) {
            throw new InvalidBallException("Opening batsmen must be in the batting team");
        }
        this.currentStriker = striker;
        this.currentNonStriker = nonStriker;
        battingStats.get(striker.getPlayerId()).comeToCrease();
        battingStats.get(nonStriker.getPlayerId()).comeToCrease();
    }

    public void setBowler(Player bowler) {
        if (!bowlingTeam.getPlayers().contains(bowler)) {
            throw new InvalidBallException("Bowler must be in the bowling team");
        }
        this.currentBowler = bowler;
    }

    /**
     * Record a ball. All state updates happen here atomically.
     */
    public synchronized void recordBall(Ball ball) {
        if (inningsClosed()) {
            throw new InvalidBallException("Innings has ended");
        }
        if (currentStriker == null || currentBowler == null) {
            throw new InvalidBallException("Striker and bowler must be set");
        }

        balls.add(ball);

        // Update batting stats for striker
        BattingStats strikerStats = battingStats.get(ball.getStriker().getPlayerId());
        strikerStats.onBallFaced(ball);

        // Update bowling stats
        BowlingStats bowlerStats = bowlingStats.get(ball.getBowler().getPlayerId());
        bowlerStats.onBallBowled(ball);

        // Update innings totals
        totalRuns += ball.getTotalRuns();
        if (ball.getExtraType() != null) totalExtras += ball.getExtraRuns();
        runsInCurrentOver += ball.getTotalRuns();

        if (ball.isLegalDelivery()) {
            legalDeliveries++;
            legalBallsInCurrentOver++;
        }

        // Handle wicket
        if (ball.isWicket()) {
            totalWickets++;
            Player out = ball.getDismissedPlayer();
            BattingStats outStats = battingStats.get(out.getPlayerId());
            Player dismisser = ball.getWicketType() == cricinfo.enums.WicketType.RUN_OUT
                ? ball.getFielderInvolved() : ball.getBowler();
            outStats.onOut(ball.getWicketType(), dismisser);

            // Replace dismissed player — caller must call newBatsman() next
            if (out.equals(currentStriker))       currentStriker = null;
            else if (out.equals(currentNonStriker)) currentNonStriker = null;
        }

        // Rotate strike on odd runs (1, 3, 5 off the bat — byes also rotate but ignore for simplicity)
        if (ball.isLegalDelivery() && ball.getRunsScored() % 2 == 1) {
            swapBatsmen();
        }

        // End of over: rotate strike, reset over counters, mark maiden if applicable
        if (legalBallsInCurrentOver == 6) {
            if (runsInCurrentOver == 0 && !hadWicketOrExtraInOver()) {
                bowlerStats.onMaidenOver();
            }
            swapBatsmen();
            runsInCurrentOver = 0;
            legalBallsInCurrentOver = 0;
            currentBowler = null;  // caller must set next bowler before next ball
        }
    }

    public void newBatsman(Player newBatsman) {
        if (!battingTeam.getPlayers().contains(newBatsman)) {
            throw new InvalidBallException("New batsman must be in batting team");
        }
        BattingStats stats = battingStats.get(newBatsman.getPlayerId());
        if (stats.getStatus() != BattingStatus.YET_TO_BAT) {
            throw new InvalidBallException("Batsman already batted");
        }
        stats.comeToCrease();
        if (currentStriker == null)       currentStriker = newBatsman;
        else if (currentNonStriker == null) currentNonStriker = newBatsman;
    }

    /** Check if innings has ended: all out OR overs exhausted. */
    public boolean inningsClosed() {
        if (inningsEnded) return true;
        int maxWickets = battingTeam.getPlayers().size() - 1;  // all out = 10 wickets
        if (totalWickets >= maxWickets) return true;
        if (maxOvers > 0 && getCompletedOvers() >= maxOvers) return true;
        return false;
    }

    public void closeInnings() {
        this.inningsEnded = true;
        // Mark remaining AT_CREASE batsmen as NOT_OUT
        for (BattingStats stats : battingStats.values()) {
            stats.markNotOut();
        }
    }

    public int getCompletedOvers() {
        return legalDeliveries / 6;
    }

    public int getCurrentOverBallCount() {
        return legalDeliveries % 6;
    }

    public double getRunRate() {
        if (legalDeliveries == 0) return 0;
        return totalRuns * 6.0 / legalDeliveries;
    }

    private void swapBatsmen() {
        Player temp = currentStriker;
        currentStriker = currentNonStriker;
        currentNonStriker = temp;
    }

    private boolean hadWicketOrExtraInOver() {
        // Scan last 6 legal balls for wicket/extras — simplified check
        int scan = Math.min(balls.size(), 6);
        for (int i = balls.size() - scan; i < balls.size(); i++) {
            Ball b = balls.get(i);
            if (b.isWicket() || b.getExtraType() == ExtraType.WIDE
                || b.getExtraType() == ExtraType.NO_BALL) return true;
        }
        return false;
    }

    // Getters
    public int getInningsNumber()                              { return inningsNumber; }
    public Team getBattingTeam()                               { return battingTeam; }
    public Team getBowlingTeam()                               { return bowlingTeam; }
    public List<Ball> getBalls()                               { return balls; }
    public int getTotalRuns()                                  { return totalRuns; }
    public int getTotalWickets()                               { return totalWickets; }
    public int getTotalExtras()                                { return totalExtras; }
    public Player getCurrentStriker()                          { return currentStriker; }
    public Player getCurrentNonStriker()                       { return currentNonStriker; }
    public Player getCurrentBowler()                           { return currentBowler; }
    public BattingStats getBattingStats(Player p)              { return battingStats.get(p.getPlayerId()); }
    public BowlingStats getBowlingStats(Player p)              { return bowlingStats.get(p.getPlayerId()); }
    public Collection<BattingStats> getAllBattingStats()       { return battingStats.values(); }
    public Collection<BowlingStats> getAllBowlingStats()       { return bowlingStats.values(); }

    public String getScoreDisplay() {
        return totalRuns + "/" + totalWickets + " ("
            + getCompletedOvers() + "." + getCurrentOverBallCount() + " ov)";
    }
}
