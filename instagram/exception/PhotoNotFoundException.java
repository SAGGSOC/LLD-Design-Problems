package instagram.exception;

public class PhotoNotFoundException extends InstagramException {
    public PhotoNotFoundException(String photoId) {
        super("Photo not found: " + photoId);
    }
}
