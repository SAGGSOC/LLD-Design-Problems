package deliverycost.exception;

public class DuplicateDriverException extends RuntimeException {
    public DuplicateDriverException(String driverId) {
        super("Driver already exists: " + driverId);
    }
}
