package airline.model;

import airline.enums.BookingStatus;
import airline.enums.FareClass;

import java.time.Instant;

public class Booking {
    private final String bookingId;        // aka "PNR" — Passenger Name Record
    private final Passenger passenger;
    private final Flight flight;
    private final FareClass fareClass;
    private final double fare;
    private final Instant createdAt;

    private BookingStatus status;
    private String paymentId;
    private String assignedSeat;           // e.g. "12A" — assigned at check-in
    private Instant checkedInAt;

    public Booking(String bookingId, Passenger passenger, Flight flight,
                   FareClass fareClass, double fare) {
        this.bookingId = bookingId;
        this.passenger = passenger;
        this.flight = flight;
        this.fareClass = fareClass;
        this.fare = fare;
        this.createdAt = Instant.now();
        this.status = BookingStatus.CONFIRMED;
    }

    public String getBookingId()       { return bookingId; }
    public Passenger getPassenger()    { return passenger; }
    public Flight getFlight()          { return flight; }
    public FareClass getFareClass()    { return fareClass; }
    public double getFare()            { return fare; }
    public Instant getCreatedAt()      { return createdAt; }
    public BookingStatus getStatus()   { return status; }
    public String getPaymentId()       { return paymentId; }
    public String getAssignedSeat()    { return assignedSeat; }
    public Instant getCheckedInAt()    { return checkedInAt; }

    public void setStatus(BookingStatus status)          { this.status = status; }
    public void setPaymentId(String paymentId)           { this.paymentId = paymentId; }
    public void setAssignedSeat(String assignedSeat)     { this.assignedSeat = assignedSeat; }
    public void setCheckedInAt(Instant checkedInAt)      { this.checkedInAt = checkedInAt; }
}
