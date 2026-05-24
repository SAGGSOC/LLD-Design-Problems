package cricinfo.model;

import cricinfo.enums.ExtraType;
import cricinfo.enums.WicketType;

import java.time.Instant;

/**
 * Atomic event in a cricket match — one delivery from bowler to batsman.
 * Immutable once created.
 *
 * Design insight: ALL stats (team score, bowler economy, batsman strike rate,
 * run rate, required rate) are DERIVED from the stream of Ball objects.
 * We never mutate stats directly — we append balls and recompute.
 *
 * Use the Builder pattern since balls have many optional fields
 * (extras, wickets, etc.).
 */
public class Ball {
    private final int ballNumber;         // 1-6 within the over (legal deliveries only)
    private final int overNumber;         // 0-indexed over
    private final Player bowler;
    private final Player striker;         // batsman facing this ball
    private final Player nonStriker;      // batsman at the other end
    private final int runsScored;         // runs off the bat (boundaries counted here)
    private final boolean isBoundary;     // true if 4 or 6
    private final ExtraType extraType;    // null if no extra
    private final int extraRuns;          // additional runs from the extra (0 if not applicable)
    private final WicketType wicketType;  // null if no wicket
    private final Player dismissedPlayer; // null if no wicket
    private final Player fielderInvolved; // null unless caught/run out
    private final Instant timestamp;

    private Ball(Builder b) {
        this.ballNumber = b.ballNumber;
        this.overNumber = b.overNumber;
        this.bowler = b.bowler;
        this.striker = b.striker;
        this.nonStriker = b.nonStriker;
        this.runsScored = b.runsScored;
        this.isBoundary = b.runsScored == 4 || b.runsScored == 6;
        this.extraType = b.extraType;
        this.extraRuns = b.extraRuns;
        this.wicketType = b.wicketType;
        this.dismissedPlayer = b.dismissedPlayer;
        this.fielderInvolved = b.fielderInvolved;
        this.timestamp = Instant.now();
    }

    /** True if this delivery counts toward the over (6 legal balls = 1 over). */
    public boolean isLegalDelivery() {
        return extraType != ExtraType.WIDE && extraType != ExtraType.NO_BALL;
    }

    /** Total runs scored on this ball — bat runs + extras. */
    public int getTotalRuns() {
        return runsScored + extraRuns;
    }

    public boolean isWicket() { return wicketType != null; }

    public int getBallNumber()             { return ballNumber; }
    public int getOverNumber()             { return overNumber; }
    public Player getBowler()              { return bowler; }
    public Player getStriker()             { return striker; }
    public Player getNonStriker()          { return nonStriker; }
    public int getRunsScored()             { return runsScored; }
    public boolean isBoundary()            { return isBoundary; }
    public ExtraType getExtraType()        { return extraType; }
    public int getExtraRuns()              { return extraRuns; }
    public WicketType getWicketType()      { return wicketType; }
    public Player getDismissedPlayer()     { return dismissedPlayer; }
    public Player getFielderInvolved()     { return fielderInvolved; }
    public Instant getTimestamp()          { return timestamp; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int ballNumber;
        private int overNumber;
        private Player bowler;
        private Player striker;
        private Player nonStriker;
        private int runsScored = 0;
        private ExtraType extraType;
        private int extraRuns = 0;
        private WicketType wicketType;
        private Player dismissedPlayer;
        private Player fielderInvolved;

        public Builder ball(int over, int ballInOver) {
            this.overNumber = over;
            this.ballNumber = ballInOver;
            return this;
        }
        public Builder bowler(Player b)            { this.bowler = b; return this; }
        public Builder striker(Player s)           { this.striker = s; return this; }
        public Builder nonStriker(Player n)        { this.nonStriker = n; return this; }
        public Builder runs(int r)                  { this.runsScored = r; return this; }
        public Builder extra(ExtraType type, int runs) {
            this.extraType = type;
            this.extraRuns = runs;
            return this;
        }
        public Builder wicket(WicketType type, Player dismissed) {
            this.wicketType = type;
            this.dismissedPlayer = dismissed;
            return this;
        }
        public Builder wicketWithFielder(WicketType type, Player dismissed, Player fielder) {
            return wicket(type, dismissed).fielder(fielder);
        }
        public Builder fielder(Player f)           { this.fielderInvolved = f; return this; }

        public Ball build() {
            if (bowler == null || striker == null || nonStriker == null) {
                throw new IllegalStateException("Bowler, striker, nonStriker are required");
            }
            return new Ball(this);
        }
    }
}
