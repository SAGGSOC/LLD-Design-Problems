package hotel.service;

import hotel.enums.BookingStatus;
import hotel.enums.RoomType;
import hotel.exception.BookingNotFoundException;
import hotel.exception.InvalidBookingException;
import hotel.exception.NoRoomAvailableException;
import hotel.model.*;
import hotel.strategy.PricingStrategy;
import hotel.strategy.RoomAssignmentStrategy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BookingService {
    private final Map<String, Booking> bookingsById = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bookingsByGuestId = new ConcurrentHashMap<>();

    private final HotelService hotelService;
    private final AvailabilityIndex availabilityIndex;
    private final RoomAssignmentStrategy assignmentStrategy;
    private final PricingStrategy pricingStrategy;

    public BookingService(HotelService hotelService,
                          AvailabilityIndex availabilityIndex,
                          RoomAssignmentStrategy assignmentStrategy,
                          PricingStrategy pricingStrategy) {
        this.hotelService = hotelService;
        this.availabilityIndex = availabilityIndex;
        this.assignmentStrategy = assignmentStrategy;
        this.pricingStrategy = pricingStrategy;
    }

    /**
     * Atomic booking: find-and-reserve happens under a single lock to prevent races.
     *
     * Race scenario: two users simultaneously book the last available Deluxe room for
     * the same dates. Without atomicity, both might see it as available and both book it.
     */
    public synchronized Booking createBooking(Guest guest, String hotelId, RoomType type,
                                               DateRange range, int guestCount) {
        if (guestCount <= 0) {
            throw new InvalidBookingException("Guest count must be positive");
        }

        // Find a candidate room
        List<Room> availableRooms = hotelService.searchAvailableRooms(hotelId, type, range);
        Optional<Room> selectedRoom = assignmentStrategy.pickRoom(availableRooms, type, range);

        if (selectedRoom.isEmpty()) {
            throw new NoRoomAvailableException(
                "No " + type + " rooms available for " + range);
        }

        Room room = selectedRoom.get();
        if (guestCount > room.getMaxOccupancy()) {
            throw new InvalidBookingException(
                "Guest count " + guestCount + " exceeds max occupancy " + room.getMaxOccupancy());
        }

        // Atomic reserve — if another booking snuck in between search and here, fail
        if (!availabilityIndex.tryReserve(room.getRoomId(), range)) {
            throw new NoRoomAvailableException(
                "Room " + room.getRoomId() + " no longer available (race)");
        }

        double totalAmount = pricingStrategy.calculatePrice(room, range);
        String bookingId = "BKG-" + UUID.randomUUID().toString().substring(0, 8);
        Booking booking = new Booking(bookingId, guest, room, range, guestCount, totalAmount);

        bookingsById.put(bookingId, booking);
        bookingsByGuestId.computeIfAbsent(guest.getGuestId(), k -> new ArrayList<>())
                         .add(bookingId);
        return booking;
    }

    public Booking checkIn(String bookingId) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new InvalidBookingException(
                "Cannot check in — booking in status " + booking.getStatus());
        }
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setCheckedInAt(Instant.now());
        booking.getRoom().markOccupied();
        return booking;
    }

    public Booking checkOut(String bookingId) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new InvalidBookingException(
                "Cannot check out — booking in status " + booking.getStatus());
        }
        booking.setStatus(BookingStatus.CHECKED_OUT);
        booking.setCheckedOutAt(Instant.now());
        booking.getRoom().markAvailable();
        return booking;
    }

    public synchronized void cancelBooking(String bookingId) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new InvalidBookingException(
                "Cannot cancel — booking in status " + booking.getStatus());
        }
        booking.setStatus(BookingStatus.CANCELLED);
        // Release the date range so the room becomes available again
        availabilityIndex.release(booking.getRoom().getRoomId(), booking.getDateRange());
    }

    public Booking getBooking(String bookingId) {
        Booking booking = bookingsById.get(bookingId);
        if (booking == null) throw new BookingNotFoundException(bookingId);
        return booking;
    }

    public List<Booking> getGuestBookings(String guestId) {
        List<String> ids = bookingsByGuestId.getOrDefault(guestId, Collections.emptyList());
        List<Booking> result = new ArrayList<>();
        for (String id : ids) result.add(bookingsById.get(id));
        return result;
    }
}
