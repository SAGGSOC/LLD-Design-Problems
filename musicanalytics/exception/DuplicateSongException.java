package musicanalytics.exception;

public class DuplicateSongException extends RuntimeException {
    public DuplicateSongException(String songId) {
        super("Song already exists: " + songId);
    }
}
