package cricinfo.model;

import cricinfo.enums.ExtraType;
import cricinfo.enums.WicketType;

public class BowlingStats {
    private final Player player;
    private int legalDeliveries;   // count of valid balls bowled (wides/no-balls don't count)
    private int runsConceded;
    private int wickets;
    private int wides;
    private int noBalls;
    private int maidenOvers;

    public BowlingStats(Player player) {
        this.player = player;
    }

    public void onBallBowled(Ball ball) {
        if (ball.isLegalDelivery()) legalDeliveries++;
        // Wides and no-balls are charged to bowler
        runsConceded += ball.getRunsScored();  // off the bat
        if (ball.getExtraType() == ExtraType.WIDE || ball.getExtraType() == ExtraType.NO_BALL) {
            runsConceded += ball.getExtraRuns();
            if (ball.getExtraType() == ExtraType.WIDE) wides++;
            if (ball.getExtraType() == ExtraType.NO_BALL) noBalls++;
        }
        // Byes / leg-byes are NOT charged to bowler
        if (ball.isWicket() && isBowlerWicket(ball.getWicketType())) {
            wickets++;
        }
    }

    public void onMaidenOver() { maidenOvers++; }

    /** Only bowled/caught/lbw/stumped/hit-wicket credit the bowler. Run-outs don't. */
    private boolean isBowlerWicket(WicketType type) {
        return type == WicketType.BOWLED
            || type == WicketType.CAUGHT
            || type == WicketType.LBW
            || type == WicketType.STUMPED
            || type == WicketType.HIT_WICKET;
    }

    public int getOversBowled()    { return legalDeliveries / 6; }
    public int getBallsInLastOver() { return legalDeliveries % 6; }

    public double getEconomy() {
        if (legalDeliveries == 0) return 0;
        return runsConceded * 6.0 / legalDeliveries;
    }

    public Player getPlayer()      { return player; }
    public int getRunsConceded()   { return runsConceded; }
    public int getWickets()        { return wickets; }
    public int getWides()          { return wides; }
    public int getNoBalls()        { return noBalls; }
    public int getMaidenOvers()    { return maidenOvers; }

    @Override
    public String toString() {
        String oversString = getOversBowled() + "." + getBallsInLastOver();
        return String.format("%s %s-%d-%d-%d (Econ %.2f)",
            player.getName(), oversString, maidenOvers, runsConceded, wickets, getEconomy());
    }
}
