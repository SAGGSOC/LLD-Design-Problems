package hotel;

import hotel.enums.RoomType;
import hotel.model.*;
import hotel.service.*;
import hotel.strategy.*;
import hotel.exception.NoRoomAvailableException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class HotelDemo {

    public static void main(String[] args) {
        // ─── Setup ───
        AvailabilityIndex availabilityIndex = new AvailabilityIndex();
        HotelService hotelService = new HotelService(availabilityIndex);
        GuestService guestService = new GuestService();

        RoomAssignmentStrategy assignmentStrategy = new LowestFloorFirstStrategy();
        PricingStrategy pricingStrategy = new WeekendSurchargePricingStrategy();

        BookingService bookingService = new BookingService(
            hotelService, availabilityIndex, assignmentStrategy, pricingStrategy);

        // Build a hotel
        hotelService.addHotel(buildGrandHotel());

        // ─── Create some guests ───
        Guest alice = guestService.registerGuest("Alice Johnson", "[email]",
            "555-0001", "PASSPORT-A123");
        Guest bob = guestService.registerGuest("Bob Smith", "[email]",
            "555-0002", "DL-B456");
        Guest charlie = guestService.registerGuest("Charlie Lee", "[email]",
            "555-0003", "PASSPORT-C789");

        // ─── Scenario 1: Happy path booking ───
        System.out.println("=== Scenario 1: Book a Deluxe room ===");
        DateRange aliceStay = new DateRange(
            LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 18));  // Fri-Sun, 3 nights
        Booking aliceBooking = bookingService.createBooking(
            alice, "HTL-001", RoomType.DELUXE, aliceStay, 2);
        System.out.println("Booking: " + aliceBooking.getBookingId());
        System.out.println("Room: " + aliceBooking.getRoom().getRoomNumber()
            + " (floor " + aliceBooking.getRoom().getFloor() + ")");
        System.out.println("Dates: " + aliceStay);
        System.out.printf("Total (3 weekend nights @ $150 × 1.5): $%.2f%n",
            aliceBooking.getTotalAmount());

        // ─── Scenario 2: Double-booking attempt ───
        System.out.println("\n=== Scenario 2: Someone else tries the same dates ===");
        // Only 2 DELUXE rooms exist. Alice has one. Bob books the second.
        Booking bobBooking = bookingService.createBooking(
            bob, "HTL-001", RoomType.DELUXE, aliceStay, 1);
        System.out.println("Bob got: " + bobBooking.getRoom().getRoomNumber());

        // Charlie tries — should fail, no more DELUXE rooms
        try {
            bookingService.createBooking(
                charlie, "HTL-001", RoomType.DELUXE, aliceStay, 1);
            System.out.println("UNEXPECTED: Charlie got a room!");
        } catch (NoRoomAvailableException e) {
            System.out.println("Charlie rejected: " + e.getMessage());
        }

        // ─── Scenario 3: Non-overlapping dates work fine ───
        System.out.println("\n=== Scenario 3: Different dates are fine ===");
        DateRange charlieStay = new DateRange(
            LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 13));
        Booking charlieBooking = bookingService.createBooking(
            charlie, "HTL-001", RoomType.DELUXE, charlieStay, 2);
        System.out.println("Charlie got: " + charlieBooking.getRoom().getRoomNumber()
            + " for " + charlieStay);

        // ─── Scenario 4: Availability search ───
        System.out.println("\n=== Scenario 4: Search availability ===");
        DateRange searchRange = new DateRange(
            LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 18));
        System.out.println("Looking for rooms on " + searchRange + ":");
        for (RoomType type : RoomType.values()) {
            int available = hotelService.getAvailableRoomCount("HTL-001", type, searchRange);
            System.out.printf("  %-10s %d available%n", type, available);
        }

        // ─── Scenario 5: Cancel a booking, room becomes available again ───
        System.out.println("\n=== Scenario 5: Alice cancels ===");
        bookingService.cancelBooking(aliceBooking.getBookingId());
        System.out.println("Alice cancelled: " + aliceBooking.getBookingId());

        int availableDeluxeAfterCancel = hotelService.getAvailableRoomCount(
            "HTL-001", RoomType.DELUXE, aliceStay);
        System.out.println("Deluxe rooms now available for "
            + aliceStay + ": " + availableDeluxeAfterCancel);

        // Charlie tries again — should succeed now
        Booking charlieRetry = bookingService.createBooking(
            charlie, "HTL-001", RoomType.DELUXE, aliceStay, 1);
        System.out.println("Charlie got " + charlieRetry.getRoom().getRoomNumber()
            + " on retry.");

        // ─── Scenario 6: Check-in and check-out ───
        System.out.println("\n=== Scenario 6: Check-in and Check-out ===");
        Booking checkInResult = bookingService.checkIn(bobBooking.getBookingId());
        System.out.println("Bob checked in at " + checkInResult.getCheckedInAt());
        System.out.println("Room " + checkInResult.getRoom().getRoomNumber()
            + " status: " + checkInResult.getRoom().getStatus());

        Booking checkOutResult = bookingService.checkOut(bobBooking.getBookingId());
        System.out.println("Bob checked out at " + checkOutResult.getCheckedOutAt());
        System.out.println("Room " + checkOutResult.getRoom().getRoomNumber()
            + " status: " + checkOutResult.getRoom().getStatus());

        // ─── Scenario 7: Guest booking history ───
        System.out.println("\n=== Scenario 7: Charlie's bookings ===");
        List<Booking> charlieBookings = bookingService.getGuestBookings(charlie.getGuestId());
        for (Booking b : charlieBookings) {
            System.out.printf("  %s: %s — %s — $%.2f (%s)%n",
                b.getBookingId(), b.getRoom().getRoomNumber(),
                b.getDateRange(), b.getTotalAmount(), b.getStatus());
        }
    }

    private static Hotel buildGrandHotel() {
        List<Room> rooms = new ArrayList<>();
        int roomNum = 101;

        // 3 STANDARD rooms on floor 1
        for (int i = 0; i < 3; i++) {
            rooms.add(new Room("RM-" + roomNum, String.valueOf(roomNum), 1,
                RoomType.STANDARD, 2, 100.0));
            roomNum++;
        }

        // 2 DELUXE rooms on floor 2
        roomNum = 201;
        for (int i = 0; i < 2; i++) {
            rooms.add(new Room("RM-" + roomNum, String.valueOf(roomNum), 2,
                RoomType.DELUXE, 3, 150.0));
            roomNum++;
        }

        // 2 SUITE rooms on floor 3
        roomNum = 301;
        for (int i = 0; i < 2; i++) {
            rooms.add(new Room("RM-" + roomNum, String.valueOf(roomNum), 3,
                RoomType.SUITE, 4, 300.0));
            roomNum++;
        }

        // 1 PENTHOUSE on floor 5
        rooms.add(new Room("RM-501", "501", 5, RoomType.PENTHOUSE, 6, 800.0));

        return new Hotel("HTL-001", "Grand Hotel", "1 Main Street, Seattle",
            4.5, rooms);
    }
}
