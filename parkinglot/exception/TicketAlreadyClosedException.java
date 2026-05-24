package parkinglot.exception;

public class TicketAlreadyClosedException extends RuntimeException {
    public TicketAlreadyClosedException(String ticketId) {
        super("Ticket already closed: " + ticketId);
    }
}
