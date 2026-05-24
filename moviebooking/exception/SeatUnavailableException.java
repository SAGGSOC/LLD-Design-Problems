package moviebooking.exception;

public class SeatUnavailableException extends MovieBookingException {
    public SeatUnavailableException(String seatId) {
        super("Seat unavailable: " + seatId);
    }
}
