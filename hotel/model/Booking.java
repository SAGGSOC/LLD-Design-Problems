package hotel.model;

import hotel.enums.BookingStatus;

import java.time.Instant;

public class Booking {
    private final String bookingId;
    private final Guest guest;
    private final Room room;
    private final DateRange dateRange;
    private final int guestCount;
    private final double totalAmount;
    private final Instant createdAt;
    private BookingStatus status;
    private Instant checkedInAt;
    private Instant checkedOutAt;

    public Booking(String bookingId, Guest guest, Room room, DateRange dateRange,
                   int guestCount, double totalAmount) {
        this.bookingId = bookingId;
        this.guest = guest;
        this.room = room;
        this.dateRange = dateRange;
        this.guestCount = guestCount;
        this.totalAmount = totalAmount;
        this.createdAt = Instant.now();
        this.status = BookingStatus.CONFIRMED;
    }

    public String getBookingId()       { return bookingId; }
    public Guest getGuest()            { return guest; }
    public Room getRoom()              { return room; }
    public DateRange getDateRange()    { return dateRange; }
    public int getGuestCount()         { return guestCount; }
    public double getTotalAmount()     { return totalAmount; }
    public Instant getCreatedAt()      { return createdAt; }
    public BookingStatus getStatus()   { return status; }
    public Instant getCheckedInAt()    { return checkedInAt; }
    public Instant getCheckedOutAt()   { return checkedOutAt; }

    public void setStatus(BookingStatus status)       { this.status = status; }
    public void setCheckedInAt(Instant t)             { this.checkedInAt = t; }
    public void setCheckedOutAt(Instant t)            { this.checkedOutAt = t; }

    public boolean isActive() {
        return status == BookingStatus.CONFIRMED || status == BookingStatus.CHECKED_IN;
    }
}
