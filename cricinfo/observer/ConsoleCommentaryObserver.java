package cricinfo.observer;

import cricinfo.model.Ball;
import cricinfo.model.Innings;

/**
 * Simple observer that prints ball-by-ball commentary to stdout.
 * Demonstrates how a live UI would subscribe to match events.
 */
public class ConsoleCommentaryObserver implements ScoreObserver {

    @Override
    public void onBall(Ball ball, Innings innings) {
        String over = innings.getCompletedOvers() + "." + innings.getCurrentOverBallCount();
        String event;
        if (ball.isWicket()) {
            event = "WICKET! " + ball.getDismissedPlayer().getName() + " out ("
                + ball.getWicketType() + ")";
        } else if (ball.getExtraType() != null) {
            event = ball.getExtraType() + " (+" + ball.getExtraRuns() + ")";
        } else {
            event = ball.getRunsScored() + " run(s)";
        }
        System.out.println("  [" + over + "] " + ball.getBowler().getName()
            + " to " + ball.getStriker().getName() + ": " + event
            + "  | Score: " + innings.getScoreDisplay());
    }

    @Override
    public void onWicket(Ball ball, Innings innings) {
        // Handled inline in onBall to avoid duplicate output
    }

    @Override
    public void onBoundary(Ball ball, Innings innings) {
        String type = ball.getRunsScored() == 6 ? "SIX" : "FOUR";
        System.out.println("  *** " + type + "! " + ball.getStriker().getName() + " ***");
    }

    @Override
    public void onInningsEnd(Innings innings) {
        System.out.println("\n=== End of innings " + innings.getInningsNumber() + " ===");
        System.out.println("Final: " + innings.getBattingTeam().getName() + " "
            + innings.getScoreDisplay());
        System.out.println();
    }

    @Override
    public void onMatchEnd(String resultSummary) {
        System.out.println("\n╔═══════════════════════════════════════╗");
        System.out.println("║  " + resultSummary);
        System.out.println("╚═══════════════════════════════════════╝");
    }
}
