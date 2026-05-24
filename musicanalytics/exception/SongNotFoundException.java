package musicanalytics.exception;

public class SongNotFoundException extends RuntimeException {
    public SongNotFoundException(String songId) {
        super("Song not found: " + songId);
    }
}
