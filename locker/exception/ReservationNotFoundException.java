package locker.exception;

public class ReservationNotFoundException extends LockerException {
    public ReservationNotFoundException(String reservationId) {
        super("Reservation not found: " + reservationId);
    }
}
