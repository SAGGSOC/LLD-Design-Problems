package locker.exception;

public class LockerNotFoundException extends LockerException {
    public LockerNotFoundException(String lockerId) {
        super("Locker not found: " + lockerId);
    }
}
