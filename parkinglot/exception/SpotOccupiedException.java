package parkinglot.exception;

public class SpotOccupiedException extends RuntimeException {
    public SpotOccupiedException(String spotId) {
        super("Spot " + spotId + " is already occupied");
    }
}
