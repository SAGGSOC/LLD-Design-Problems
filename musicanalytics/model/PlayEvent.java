package musicanalytics.model;

/**
 * Immutable record of a single play event.
 */
public class PlayEvent {
    private final String userId;
    private final String songId;
    private final long timestamp;

    public PlayEvent(String userId, String songId, long timestamp) {
        this.userId = userId;
        this.songId = songId;
        this.timestamp = timestamp;
    }

    public String getUserId() { return userId; }
    public String getSongId() { return songId; }
    public long getTimestamp() { return timestamp; }
}
