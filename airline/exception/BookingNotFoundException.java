package airline.exception;

public class BookingNotFoundException extends AirlineException {
    public BookingNotFoundException(String bookingId) {
        super("Booking not found: " + bookingId);
    }
}
