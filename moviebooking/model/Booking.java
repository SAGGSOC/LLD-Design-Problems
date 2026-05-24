package moviebooking.model;

import moviebooking.enums.BookingStatus;

import java.time.Instant;
import java.util.List;

public class Booking {
    private final String bookingId;
    private final User user;
    private final Show show;
    private final List<Seat> seats;
    private final double totalAmount;
    private final Instant createdAt;
    private BookingStatus status;
    private String paymentId;

    public Booking(String bookingId, User user, Show show, List<Seat> seats,
                   double totalAmount) {
        this.bookingId = bookingId;
        this.user = user;
        this.show = show;
        this.seats = seats;
        this.totalAmount = totalAmount;
        this.createdAt = Instant.now();
        this.status = BookingStatus.PENDING;
    }

    public String getBookingId()      { return bookingId; }
    public User getUser()             { return user; }
    public Show getShow()             { return show; }
    public List<Seat> getSeats()      { return seats; }
    public double getTotalAmount()    { return totalAmount; }
    public Instant getCreatedAt()     { return createdAt; }
    public BookingStatus getStatus()  { return status; }
    public String getPaymentId()      { return paymentId; }

    public void setStatus(BookingStatus status)    { this.status = status; }
    public void setPaymentId(String paymentId)     { this.paymentId = paymentId; }
}
