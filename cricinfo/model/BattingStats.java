package cricinfo.model;

import cricinfo.enums.BattingStatus;
import cricinfo.enums.WicketType;

/**
 * Per-player batting stats for a single innings.
 * Mutable — updated as balls are recorded.
 *
 * Could be computed on-demand from balls, but we cache for O(1) lookup
 * since the live UI queries these constantly.
 */
public class BattingStats {
    private final Player player;
    private int runsScored;
    private int ballsFaced;     // legal deliveries faced (wides don't count)
    private int fours;
    private int sixes;
    private BattingStatus status;
    private WicketType dismissalType;
    private Player dismissedBy;

    public BattingStats(Player player) {
        this.player = player;
        this.status = BattingStatus.YET_TO_BAT;
    }

    public void onBallFaced(Ball ball) {
        // Batsman only credited runs scored off the bat (not extras)
        runsScored += ball.getRunsScored();
        if (ball.isLegalDelivery()) {
            ballsFaced++;
        }
        if (ball.getRunsScored() == 4) fours++;
        if (ball.getRunsScored() == 6) sixes++;
    }

    public void onOut(WicketType type, Player dismisser) {
        this.status = BattingStatus.OUT;
        this.dismissalType = type;
        this.dismissedBy = dismisser;
    }

    public void comeToCrease() {
        this.status = BattingStatus.AT_CREASE;
    }

    public void markNotOut() {
        if (status == BattingStatus.AT_CREASE) this.status = BattingStatus.NOT_OUT;
    }

    public double getStrikeRate() {
        return ballsFaced == 0 ? 0.0 : (runsScored * 100.0 / ballsFaced);
    }

    public Player getPlayer()                  { return player; }
    public int getRunsScored()                 { return runsScored; }
    public int getBallsFaced()                 { return ballsFaced; }
    public int getFours()                      { return fours; }
    public int getSixes()                      { return sixes; }
    public BattingStatus getStatus()           { return status; }
    public WicketType getDismissalType()       { return dismissalType; }
    public Player getDismissedBy()             { return dismissedBy; }

    @Override
    public String toString() {
        String suffix = status == BattingStatus.OUT ? "" : "*";
        return String.format("%s %d%s (%d balls, SR %.1f)",
            player.getName(), runsScored, suffix, ballsFaced, getStrikeRate());
    }
}
