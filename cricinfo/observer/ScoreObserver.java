package cricinfo.observer;

import cricinfo.model.Ball;
import cricinfo.model.Innings;

/**
 * Subscribers to live match updates. Classic Observer pattern.
 * Implementations might push to: WebSocket, SMS, mobile push, scoreboard display.
 */
public interface ScoreObserver {
    void onBall(Ball ball, Innings innings);
    void onWicket(Ball ball, Innings innings);
    void onBoundary(Ball ball, Innings innings);
    void onInningsEnd(Innings innings);
    void onMatchEnd(String resultSummary);
}
