package cricinfo.model;

import cricinfo.enums.MatchFormat;
import cricinfo.enums.MatchResult;
import cricinfo.enums.MatchStatus;
import cricinfo.exception.InvalidBallException;
import cricinfo.observer.ScoreObserver;
import cricinfo.strategy.MatchFormatFactory;
import cricinfo.strategy.MatchFormatRules;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The orchestrator. Owns all innings of a single match.
 * Delegates ball-by-ball state to the current Innings.
 * Uses MatchFormatRules (Strategy) to know format-specific rules.
 * Publishes events to ScoreObservers.
 */
public class Match {
    private final String matchId;
    private final Team teamA;
    private final Team teamB;
    private final MatchFormat format;
    private final MatchFormatRules rules;
    private final String venue;

    private final List<Innings> innings = new ArrayList<>();
    private final List<ScoreObserver> observers = new CopyOnWriteArrayList<>();

    private MatchStatus status;
    private MatchResult result;
    private Team tossWinner;
    private int currentInningsIndex = -1;

    public Match(String matchId, Team teamA, Team teamB, MatchFormat format, String venue) {
        this.matchId = matchId;
        this.teamA = teamA;
        this.teamB = teamB;
        this.format = format;
        this.rules = MatchFormatFactory.getRules(format);
        this.venue = venue;
        this.status = MatchStatus.SCHEDULED;
    }

    // ──────────────────────── Lifecycle ────────────────────────

    public void setToss(Team winner) { this.tossWinner = winner; }

    public Innings startInnings(Team battingTeam, Team bowlingTeam) {
        if (status == MatchStatus.COMPLETED) {
            throw new InvalidBallException("Match already completed");
        }
        if (innings.size() >= rules.getMaxInnings()) {
            throw new InvalidBallException("All innings completed for this format");
        }

        // Close any in-progress innings
        if (currentInningsIndex >= 0 && !innings.get(currentInningsIndex).inningsClosed()) {
            throw new InvalidBallException("Current innings not yet closed");
        }

        Innings newInnings = new Innings(innings.size() + 1, battingTeam, bowlingTeam,
            rules.getMaxOversPerInnings());
        innings.add(newInnings);
        currentInningsIndex = innings.size() - 1;

        if (status == MatchStatus.SCHEDULED) status = MatchStatus.IN_PROGRESS;
        return newInnings;
    }

    public Innings getCurrentInnings() {
        if (currentInningsIndex < 0) throw new InvalidBallException("No innings started");
        return innings.get(currentInningsIndex);
    }

    /**
     * Record a ball in the current innings. Updates stats and notifies observers.
     */
    public void recordBall(Ball ball) {
        Innings currentInnings = getCurrentInnings();
        currentInnings.recordBall(ball);

        // Notify observers
        for (ScoreObserver obs : observers) {
            obs.onBall(ball, currentInnings);
            if (ball.isBoundary()) obs.onBoundary(ball, currentInnings);
            if (ball.isWicket())   obs.onWicket(ball, currentInnings);
        }

        // Check if chase was just completed (second innings surpassed target)
        // In T20/ODI, the chasing team wins the moment they pass the target.
        if (rules.getMaxInnings() == 2 && innings.size() == 2
                && !currentInnings.inningsClosed()) {
            Innings firstInnings = innings.get(0);
            if (currentInnings.getTotalRuns() > firstInnings.getTotalRuns()) {
                currentInnings.closeInnings();
                for (ScoreObserver obs : observers) obs.onInningsEnd(currentInnings);
                endMatch();
                return;
            }
        }

        // Check innings end (overs exhausted, all out)
        if (currentInnings.inningsClosed()) {
            currentInnings.closeInnings();
            for (ScoreObserver obs : observers) obs.onInningsEnd(currentInnings);

            // Check match end
            if (isMatchComplete()) {
                endMatch();
            }
        }
    }

    // ──────────────────────── Result determination ────────────────────────

    private boolean isMatchComplete() {
        // Match complete when max innings played, OR chasing team surpasses target
        if (innings.size() >= rules.getMaxInnings()) return true;

        // Second-innings chase logic (T20/ODI)
        if (rules.getMaxInnings() == 2 && innings.size() == 2) {
            Innings second = innings.get(1);
            Innings first = innings.get(0);
            // Chase successful — end match
            if (second.getTotalRuns() > first.getTotalRuns()) return true;
        }
        return false;
    }

    private void endMatch() {
        this.result = determineResult();
        this.status = MatchStatus.COMPLETED;
        String summary = buildResultSummary();
        for (ScoreObserver obs : observers) obs.onMatchEnd(summary);
    }

    private MatchResult determineResult() {
        int scoreA = getTotalScoreForTeam(teamA);
        int scoreB = getTotalScoreForTeam(teamB);

        if (scoreA > scoreB) return MatchResult.TEAM_A_WINS;
        if (scoreB > scoreA) return MatchResult.TEAM_B_WINS;
        return MatchResult.TIE;
    }

    private int getTotalScoreForTeam(Team team) {
        return innings.stream()
            .filter(i -> i.getBattingTeam().equals(team))
            .mapToInt(Innings::getTotalRuns)
            .sum();
    }

    private String buildResultSummary() {
        int scoreA = getTotalScoreForTeam(teamA);
        int scoreB = getTotalScoreForTeam(teamB);

        switch (result) {
            case TEAM_A_WINS:
                return teamA.getName() + " beat " + teamB.getName()
                    + " by " + (scoreA - scoreB) + " runs";
            case TEAM_B_WINS:
                // For a chase: "by X wickets"
                if (rules.getMaxInnings() == 2 && innings.size() == 2
                    && innings.get(1).getBattingTeam().equals(teamB)) {
                    int wicketsRemaining = teamB.getPlayers().size() - 1
                        - innings.get(1).getTotalWickets();
                    return teamB.getName() + " beat " + teamA.getName()
                        + " by " + wicketsRemaining + " wickets";
                }
                return teamB.getName() + " beat " + teamA.getName()
                    + " by " + (scoreB - scoreA) + " runs";
            case TIE:  return "Match tied — both teams scored " + scoreA;
            case DRAW: return "Match drawn";
            default:   return "No result";
        }
    }

    // ──────────────────────── Observer registration ────────────────────────

    public void subscribe(ScoreObserver observer)   { observers.add(observer); }
    public void unsubscribe(ScoreObserver observer) { observers.remove(observer); }

    // ──────────────────────── Getters ────────────────────────

    public String getMatchId()                { return matchId; }
    public Team getTeamA()                    { return teamA; }
    public Team getTeamB()                    { return teamB; }
    public MatchFormat getFormat()            { return format; }
    public String getVenue()                  { return venue; }
    public MatchStatus getStatus()            { return status; }
    public MatchResult getResult()            { return result; }
    public Team getTossWinner()               { return tossWinner; }
    public List<Innings> getAllInnings()      { return innings; }
}
