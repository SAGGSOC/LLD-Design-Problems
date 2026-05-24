package instagram.exception;

public class UserNotFoundException extends InstagramException {
    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}
