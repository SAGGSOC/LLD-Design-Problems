package moviebooking.service;

import moviebooking.enums.BookingStatus;
import moviebooking.enums.PaymentStatus;
import moviebooking.exception.BookingNotFoundException;
import moviebooking.exception.PaymentFailedException;
import moviebooking.exception.SeatUnavailableException;
import moviebooking.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main orchestrator for the booking flow.
 *
 * Booking is a two-phase process:
 *   1. holdSeats(user, show, seatIds) — atomically hold all requested seats.
 *      If any one seat can't be held, RELEASE all previously-held seats in this
 *      request (all-or-nothing). Returns a PENDING booking with 5-min timeout.
 *
 *   2. confirmPayment(bookingId, payment) — if payment succeeds, seats become
 *      BOOKED. If payment fails, held seats are released.
 *
 * If the user abandons step 2 (doesn't pay), seats auto-release after the
 * hold duration expires (default 5 minutes).
 */
public class BookingService {
    private static final long HOLD_DURATION_SECONDS = 300;  // 5 minutes

    private final Map<String, Booking> bookingsById = new ConcurrentHashMap<>();
    private final PaymentGateway paymentGateway;

    public BookingService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    /**
     * Atomically hold all requested seats. All-or-nothing:
     * if any seat can't be held, roll back and release all held seats in this request.
     */
    public Booking holdSeats(User user, Show show, List<String> seatIds) {
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("Must select at least one seat");
        }

        // Sort seatIds to prevent circular wait / deadlock between concurrent requests
        // trying to hold overlapping seat sets in different orders.
        List<String> sortedSeatIds = new ArrayList<>(seatIds);
        sortedSeatIds.sort(String::compareTo);

        List<String> successfullyHeld = new ArrayList<>();
        try {
            for (String seatId : sortedSeatIds) {
                boolean heldOk = show.tryHoldSeat(seatId, user.getUserId(), HOLD_DURATION_SECONDS);
                if (!heldOk) {
                    throw new SeatUnavailableException(seatId);
                }
                successfullyHeld.add(seatId);
            }

            // Compute total price
            List<Seat> heldSeats = new ArrayList<>();
            double total = 0;
            for (String seatId : successfullyHeld) {
                Seat seat = show.getSeat(seatId);
                heldSeats.add(seat);
                total += show.calculatePrice(seat);
            }

            // Create PENDING booking
            String bookingId = "BKG-" + UUID.randomUUID().toString().substring(0, 8);
            Booking booking = new Booking(bookingId, user, show, heldSeats, total);
            bookingsById.put(bookingId, booking);
            return booking;

        } catch (SeatUnavailableException e) {
            // Roll back: release all seats we successfully held before failing
            for (String seatId : successfullyHeld) {
                show.releaseSeat(seatId, user.getUserId());
            }
            throw e;
        }
    }

    /**
     * Attempt payment. On success, confirm all held seats as BOOKED.
     * On failure, release all held seats.
     */
    public Booking confirmPayment(String bookingId) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new PaymentFailedException(
                "Cannot confirm — booking in status " + booking.getStatus());
        }

        Payment payment = paymentGateway.charge(bookingId, booking.getTotalAmount());
        booking.setPaymentId(payment.getPaymentId());

        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            // Confirm all seats
            for (Seat seat : booking.getSeats()) {
                booking.getShow().confirmSeat(seat.getSeatId(), booking.getUser().getUserId());
            }
            booking.setStatus(BookingStatus.CONFIRMED);
            return booking;
        } else {
            // Payment failed — release all held seats
            releaseSeats(booking);
            booking.setStatus(BookingStatus.CANCELLED);
            throw new PaymentFailedException("Payment failed for booking " + bookingId);
        }
    }

    /**
     * User cancels a PENDING booking. Releases held seats.
     */
    public void cancelBooking(String bookingId) {
        Booking booking = getBooking(bookingId);
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            // Confirmed booking — refund and release
            paymentGateway.refund(booking.getPaymentId(), booking.getTotalAmount());
            for (Seat seat : booking.getSeats()) {
                booking.getShow().releaseBookedSeat(seat.getSeatId());
            }
        } else if (booking.getStatus() == BookingStatus.PENDING) {
            releaseSeats(booking);
        }
        booking.setStatus(BookingStatus.CANCELLED);
    }

    public Booking getBooking(String bookingId) {
        Booking booking = bookingsById.get(bookingId);
        if (booking == null) throw new BookingNotFoundException(bookingId);
        return booking;
    }

    private void releaseSeats(Booking booking) {
        for (Seat seat : booking.getSeats()) {
            booking.getShow().releaseSeat(seat.getSeatId(), booking.getUser().getUserId());
        }
    }
}
