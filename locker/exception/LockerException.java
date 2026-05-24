package locker.exception;

/** Base exception for all locker-system errors. */
public class LockerException extends RuntimeException {
    public LockerException(String message) { super(message); }
}
