import java.util.*;

/**
 * Doctor Appointment System — In-Memory (Interview Style)
 *
 * Features:
 *   - Register doctors with speciality and time slots
 *   - Book appointments (with waitlist support)
 *   - Cancel appointments (auto-promotes from waitlist)
 *   - View availability by speciality
 *   - View appointments by patient or doctor
 *
 * Design:
 *   - FIFO waitlist per (doctor, slot) using LinkedList
 *   - Patient conflict check: can't book same time slot with different doctors
 *   - Slots are 60-min blocks in 24h format (09:00 - 21:00)
 */
public class DoctorAppointmentSystem {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class Doctor {
        final String name;
        String speciality;
        Set<String> slots; // available start times, e.g. "09:00"

        Doctor(String name, String speciality, Set<String> slots) {
            this.name = name;
            this.speciality = speciality;
            this.slots = slots;
        }
    }

    static class Booking {
        final String bookingId;
        final String patientId;
        final String doctorName;
        final String startTime;
        boolean confirmed; // true = booked, false = waitlisted
        boolean cancelled;

        Booking(String bookingId, String patientId, String doctorName, String startTime, boolean confirmed) {
            this.bookingId = bookingId;
            this.patientId = patientId;
            this.doctorName = doctorName;
            this.startTime = startTime;
            this.confirmed = confirmed;
            this.cancelled = false;
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Map<String, Doctor> doctors = new LinkedHashMap<>();          // doctorName → Doctor
    private final Map<String, Booking> bookings = new LinkedHashMap<>();        // bookingId → Booking
    // Key: "doctorName|startTime" → confirmed bookingId (or null if free)
    private final Map<String, String> slotBookings = new HashMap<>();
    // Key: "doctorName|startTime" → FIFO waitlist of bookingIds
    private final Map<String, LinkedList<String>> waitlists = new HashMap<>();
    // Key: "patientId|startTime" → confirmed bookingId (for patient conflict check)
    private final Map<String, String> patientSlots = new HashMap<>();

    // ═══════════════════════════════════════════════
    // 1. Register Doctor
    // ═══════════════════════════════════════════════

    public void registerDoctor(String doctorName, String speciality, List<String> slots) {
        Set<String> startTimes = new LinkedHashSet<>();
        for (String slot : slots) {
            String startTime = slot.split("-")[0]; // "12:00-13:00" → "12:00"
            startTimes.add(startTime);
        }

        // If same doctor added again, overwrite
        doctors.put(doctorName, new Doctor(doctorName, speciality, startTimes));
    }

    // ═══════════════════════════════════════════════
    // 2. Book Appointment
    // ═══════════════════════════════════════════════

    public String bookAppointment(String bookingId, String patientId, String doctorName,
                                   String startTime, boolean addToWaitlistIfBooked) {
        Doctor doctor = doctors.get(doctorName);
        if (doctor == null) return "Doctor not found";
        if (!doctor.slots.contains(startTime)) return "Slot not available";

        String slotKey = doctorName + "|" + startTime;
        String patientKey = patientId + "|" + startTime;

        // Check if patient already has a confirmed appointment at this time with another doctor
        String existingPatientBooking = patientSlots.get(patientKey);
        if (existingPatientBooking != null) {
            Booking existing = bookings.get(existingPatientBooking);
            if (existing != null && existing.confirmed && !existing.cancelled
                && !existing.doctorName.equals(doctorName)) {
                return "Slot already booked";
            }
        }

        // Check if this doctor's slot is free
        String confirmedBookingId = slotBookings.get(slotKey);
        if (confirmedBookingId == null) {
            // Slot is free — book it
            Booking booking = new Booking(bookingId, patientId, doctorName, startTime, true);
            bookings.put(bookingId, booking);
            slotBookings.put(slotKey, bookingId);
            patientSlots.put(patientKey, bookingId);
            return "BOOKED";
        } else {
            // Slot is taken
            if (addToWaitlistIfBooked) {
                Booking booking = new Booking(bookingId, patientId, doctorName, startTime, false);
                bookings.put(bookingId, booking);
                waitlists.computeIfAbsent(slotKey, k -> new LinkedList<>()).add(bookingId);
                return "Added to the waitlist";
            } else {
                return "Slot already booked";
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 3. Show Availability by Speciality
    // ═══════════════════════════════════════════════

    public List<String> showAvailabilityBySpeciality(String speciality) {
        List<String[]> available = new ArrayList<>(); // [startTime, doctorName, endTime]

        for (Doctor doctor : doctors.values()) {
            if (!doctor.speciality.equals(speciality)) continue;

            for (String startTime : doctor.slots) {
                String slotKey = doctor.name + "|" + startTime;
                if (!slotBookings.containsKey(slotKey)) {
                    String endTime = getEndTime(startTime);
                    available.add(new String[]{startTime, doctor.name, endTime});
                }
            }
        }

        // Sort by start time ascending, tie-break by doctor name ascending
        available.sort((a, b) -> {
            int cmp = a[0].compareTo(b[0]);
            if (cmp != 0) return cmp;
            return a[1].compareTo(b[1]);
        });

        List<String> result = new ArrayList<>();
        for (String[] entry : available) {
            result.add("Dr." + entry[1] + ": (" + entry[0] + "-" + entry[2] + ")");
        }
        return result;
    }

    // ═══════════════════════════════════════════════
    // 4. Cancel Booking
    // ═══════════════════════════════════════════════

    public List<String> cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null || booking.cancelled) {
            return Collections.singletonList("Invalid booking id");
        }

        List<String> messages = new ArrayList<>();
        booking.cancelled = true;

        String slotKey = booking.doctorName + "|" + booking.startTime;
        String patientKey = booking.patientId + "|" + booking.startTime;

        if (booking.confirmed) {
            // Remove from confirmed slot
            slotBookings.remove(slotKey);
            patientSlots.remove(patientKey);
            messages.add("Booking Cancelled");

            // Promote from waitlist (FIFO)
            LinkedList<String> waitlist = waitlists.get(slotKey);
            if (waitlist != null && !waitlist.isEmpty()) {
                String promotedId = waitlist.poll();
                Booking promoted = bookings.get(promotedId);

                if (promoted != null && !promoted.cancelled) {
                    promoted.confirmed = true;
                    slotBookings.put(slotKey, promotedId);
                    String promotedPatientKey = promoted.patientId + "|" + promoted.startTime;
                    patientSlots.put(promotedPatientKey, promotedId);
                    messages.add("Booking confirmed for Booking id: " + promotedId);
                }
            }
        } else {
            // Was on waitlist — remove from waitlist
            LinkedList<String> waitlist = waitlists.get(slotKey);
            if (waitlist != null) {
                waitlist.remove(bookingId);
            }
            messages.add("Booking Cancelled");
        }

        return messages;
    }

    // ═══════════════════════════════════════════════
    // 5. Show Appointments Booked
    // ═══════════════════════════════════════════════

    public List<String> showAppointmentsBooked(String userName) {
        List<String> result = new ArrayList<>();

        // Check if userName is a doctor
        Doctor doctor = doctors.get(userName);
        if (doctor != null) {
            // Show all confirmed bookings for this doctor
            for (Booking b : bookings.values()) {
                if (b.doctorName.equals(userName) && b.confirmed && !b.cancelled) {
                    result.add("Booking id: " + b.bookingId + ", " + b.patientId + " " + b.startTime);
                }
            }
        } else {
            // Assume it's a patient
            for (Booking b : bookings.values()) {
                if (b.patientId.equals(userName) && b.confirmed && !b.cancelled) {
                    result.add("Booking id: " + b.bookingId + ", Dr " + b.doctorName + " " + b.startTime);
                }
            }
        }

        return result;
    }

    // ═══════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════

    private String getEndTime(String startTime) {
        int hour = Integer.parseInt(startTime.split(":")[0]);
        return String.format("%02d:00", hour + 1);
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        DoctorAppointmentSystem system = new DoctorAppointmentSystem();

        // ─── Example 1: Register + View Availability ───
        System.out.println("═══ Example 1: Register & View ═══\n");

        system.registerDoctor("Curious", "Cardiologist", Arrays.asList("12:00-13:00", "16:00-17:00"));
        system.registerDoctor("Alpha", "Cardiologist", Arrays.asList("09:00-10:00"));

        List<String> avail = system.showAvailabilityBySpeciality("Cardiologist");
        System.out.println("Availability (Cardiologist):");
        avail.forEach(s -> System.out.println("  " + s));
        // Expected: Dr.Alpha: (09:00-10:00), Dr.Curious: (12:00-13:00), Dr.Curious: (16:00-17:00)

        // ─── Example 2: Book + Verify ───
        System.out.println("\n═══ Example 2: Book & Verify ═══\n");

        String res = system.bookAppointment("b1", "PatientA", "Alpha", "09:00", false);
        System.out.println("Book b1: " + res); // BOOKED

        avail = system.showAvailabilityBySpeciality("Cardiologist");
        System.out.println("Availability after booking:");
        avail.forEach(s -> System.out.println("  " + s));
        // Alpha's slot gone

        System.out.println("PatientA appointments:");
        system.showAppointmentsBooked("PatientA").forEach(s -> System.out.println("  " + s));

        System.out.println("Dr Alpha appointments:");
        system.showAppointmentsBooked("Alpha").forEach(s -> System.out.println("  " + s));

        // ─── Example 3: Waitlist Promotion ───
        System.out.println("\n═══ Example 3: Waitlist Promotion ═══\n");

        res = system.bookAppointment("b2", "PatientB", "Curious", "12:00", false);
        System.out.println("Book b2: " + res); // BOOKED

        res = system.bookAppointment("b3", "PatientC", "Curious", "12:00", true);
        System.out.println("Book b3: " + res); // Added to the waitlist

        List<String> cancelResult = system.cancelBooking("b2");
        System.out.println("Cancel b2: " + cancelResult);
        // [Booking Cancelled, Booking confirmed for Booking id: b3]

        System.out.println("PatientC appointments:");
        system.showAppointmentsBooked("PatientC").forEach(s -> System.out.println("  " + s));

        System.out.println("Dr Curious appointments:");
        system.showAppointmentsBooked("Curious").forEach(s -> System.out.println("  " + s));

        // ─── Edge Case: Patient conflict ───
        System.out.println("\n═══ Edge: Patient Conflict ═══\n");

        system.registerDoctor("Beta", "Dermatologist", Arrays.asList("09:00-10:00"));
        res = system.bookAppointment("b4", "PatientA", "Beta", "09:00", false);
        System.out.println("PatientA tries Beta 09:00 (already has Alpha 09:00): " + res);
        // Slot already booked (patient conflict)

        // ─── Edge Case: Invalid cancel ───
        System.out.println("\n═══ Edge: Invalid Cancel ═══\n");
        List<String> invalidCancel = system.cancelBooking("b999");
        System.out.println("Cancel b999: " + invalidCancel);
    }
}
