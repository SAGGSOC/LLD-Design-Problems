package deliverycost.exception;

public class DriverNotFoundException extends RuntimeException {
    public DriverNotFoundException(String driverId) {
        super("Driver not found: " + driverId);
    }
}
