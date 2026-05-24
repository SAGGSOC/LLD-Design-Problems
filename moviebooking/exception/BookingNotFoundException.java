package moviebooking.exception;

public class BookingNotFoundException extends MovieBookingException {
    public BookingNotFoundException(String bookingId) {
        super("Booking not found: " + bookingId);
    }
}
