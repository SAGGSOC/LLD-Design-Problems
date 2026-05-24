package hotel.exception;

public class BookingNotFoundException extends HotelException {
    public BookingNotFoundException(String bookingId) {
        super("Booking not found: " + bookingId);
    }
}
