package airline.service;

import airline.enums.BookingStatus;
import airline.enums.FareClass;
import airline.enums.PaymentStatus;
import airline.exception.BookingNotFoundException;
import airline.exception.InvalidBookingException;
import airline.exception.NoSeatsAvailableException;
import airline.model.Booking;
import airline.model.Flight;
import airline.model.Passenger;
import airline.model.Payment;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the booking lifecycle:
 *   bookFlight → reserve seat → charge → confirm OR rollback on failure
 *   cancelBooking → refund → release seat
 *   checkIn → assign specific seat → status = CHECKED_IN
 */
public class BookingService {
    private final Map<String, Booking> bookingsById = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bookingsByPassenger = new ConcurrentHashMap<>();

    // Per-flight seat-number counter for check-in assignment (e.g., 1A, 1B, ...)
    // In production this would be a seat map with row/column layout.
    private final Map<String, AtomicInteger> seatCounterByFlight = new ConcurrentHashMap<>();

    private final PaymentGateway paymentGateway;

    public BookingService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    /**
     * Book a flight. Atomically:
     *   1. Reserve seat via FlightInventory.reserveSeat() (CAS-based, lock-free)
     *   2. Charge payment
     *   3. If payment fails, release the seat (rollback)
     *   4. Otherwise, create CONFIRMED booking
     */
    public Booking bookFlight(Passenger passenger, Flight flight, FareClass fareClass) {
        if (!flight.isBookable()) {
            throw new InvalidBookingException(
                "Flight " + flight.getFlightNumber() + " is not bookable (status="
                + flight.getStatus() + ")");
        }

        // Step 1: atomically reserve a seat
        if (!flight.reserveSeat(fareClass)) {
            throw new NoSeatsAvailableException(
                "No " + fareClass + " seats on " + flight.getFlightNumber());
        }

        // Step 2: compute fare and attempt payment
        double fare = flight.getFare(fareClass);
        String bookingId = "PNR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Payment payment = paymentGateway.charge(bookingId, fare);

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            // Step 3: Rollback — payment failed, release the seat
            flight.releaseSeat(fareClass);
            throw new InvalidBookingException(
                "Payment failed for booking " + bookingId);
        }

        // Step 4: Create booking
        Booking booking = new Booking(bookingId, passenger, flight, fareClass, fare);
        booking.setPaymentId(payment.getPaymentId());
        bookingsById.put(bookingId, booking);
        bookingsByPassenger
            .computeIfAbsent(passenger.getPassengerId(), k -> new ArrayList<>())
            .add(bookingId);
        return booking;
    }

    public void cancelBooking(String bookingId) {
        Booking booking = getBooking(bookingId);

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new InvalidBookingException("Booking already cancelled");
        }
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new InvalidBookingException("Cannot cancel a completed booking");
        }

        // Refund
        paymentGateway.refund(booking.getPaymentId(), booking.getFare());

        // Release seat back to inventory
        booking.getFlight().releaseSeat(booking.getFareClass());
        booking.setStatus(BookingStatus.CANCELLED);
    }

    /**
     * Check-in assigns a specific seat and transitions the booking to CHECKED_IN.
     * Must happen within the 24h check-in window.
     */
    public synchronized Booking checkIn(String bookingId) {
        Booking booking = getBooking(bookingId);

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new InvalidBookingException(
                "Cannot check in — booking status is " + booking.getStatus());
        }

        Flight flight = booking.getFlight();
        if (!flight.canCheckIn()) {
            throw new InvalidBookingException(
                "Check-in not open for flight " + flight.getFlightNumber());
        }

        // Assign seat (simple row/column generator per flight)
        String seatAssignment = generateSeatAssignment(flight.getFlightNumber(),
                                                        booking.getFareClass());
        booking.setAssignedSeat(seatAssignment);
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setCheckedInAt(Instant.now());
        return booking;
    }

    public Booking getBooking(String bookingId) {
        Booking booking = bookingsById.get(bookingId);
        if (booking == null) throw new BookingNotFoundException(bookingId);
        return booking;
    }

    public List<Booking> getPassengerBookings(String passengerId) {
        List<String> ids = bookingsByPassenger.getOrDefault(passengerId, Collections.emptyList());
        List<Booking> result = new ArrayList<>();
        for (String id : ids) {
            Booking b = bookingsById.get(id);
            if (b != null) result.add(b);
        }
        return result;
    }

    /**
     * Simple seat assignment: FIRST → rows 1-2, BUSINESS → rows 3-6, ECONOMY → rows 7+
     * Uses a per-flight counter for sequential assignment.
     * In production: real seat map with specific row/column.
     */
    private String generateSeatAssignment(String flightNumber, FareClass fareClass) {
        AtomicInteger counter = seatCounterByFlight
            .computeIfAbsent(flightNumber, k -> new AtomicInteger(0));
        int seqNum = counter.incrementAndGet();

        int rowStart;
        switch (fareClass) {
            case FIRST:    rowStart = 1; break;
            case BUSINESS: rowStart = 3; break;
            case ECONOMY:  rowStart = 7; break;
            default:       rowStart = 7;
        }
        int row = rowStart + (seqNum - 1) / 6;
        char col = (char) ('A' + (seqNum - 1) % 6);
        return row + String.valueOf(col);
    }
}
