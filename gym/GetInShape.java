import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * GetInShape — In-Memory Fitness Booking Platform (Interview Style)
 *
 * Features:
 *   1. Onboard centers with timings and workout types
 *   2. Admin defines workout slots (with overlap/timing validation)
 *   3. View availability (sorted by start time or seats available)
 *   4. Book / Cancel sessions
 *   5. Notify-me interest list (Observer pattern)
 *
 * Concurrency:
 *   - ConcurrentHashMap for centers and slot index (safe concurrent reads/writes)
 *   - ReentrantLock per WorkoutSlot (book/cancel/interest are short critical sections)
 *   - Per-slot locking: booking yoga at center A doesn't block booking weights at center B
 */
public class GetInShape {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class TimeRange {
        final int start;
        final int end;

        TimeRange(int start, int end) {
            this.start = start;
            this.end = end;
        }

        boolean contains(int s, int e) {
            return s >= start && e <= end;
        }

        boolean overlaps(int s, int e) {
            return s < end && e > start;
        }
    }

    static class WorkoutSlot {
        final String centerName;
        final String workoutType;
        final int startTime;
        final int endTime;
        final int totalSeats;
        int seatsAvailable;
        final Set<String> bookedUsers;         // userId set
        final List<String> interestList;       // ordered list of interested userIds
        final ReentrantLock lock;              // per-slot lock for book/cancel/interest

        WorkoutSlot(String centerName, String workoutType, int startTime, int endTime, int totalSeats) {
            this.centerName = centerName;
            this.workoutType = workoutType;
            this.startTime = startTime;
            this.endTime = endTime;
            this.totalSeats = totalSeats;
            this.seatsAvailable = totalSeats;
            this.bookedUsers = new HashSet<>();
            this.interestList = new ArrayList<>();
            this.lock = new ReentrantLock();
        }

        String getKey() {
            return centerName + "|" + workoutType + "|" + startTime + "|" + endTime;
        }

        @Override
        public String toString() {
            return centerName + "|" + workoutType + "|" + startTime + "|" + endTime + "|" + seatsAvailable;
        }
    }

    static class Center {
        final String name;
        final List<TimeRange> timings;
        final Set<String> allowedWorkoutTypes;
        final List<WorkoutSlot> slots;

        Center(String name, List<TimeRange> timings, Set<String> allowedWorkoutTypes) {
            this.name = name;
            this.timings = timings;
            this.allowedWorkoutTypes = allowedWorkoutTypes;
            this.slots = new ArrayList<>();
        }

        boolean isWithinTimings(int start, int end) {
            for (TimeRange range : timings) {
                if (range.contains(start, end)) return true;
            }
            return false;
        }

        boolean hasOverlap(int start, int end) {
            for (WorkoutSlot slot : slots) {
                if (start < slot.endTime && end > slot.startTime) return true;
            }
            return false;
        }
    }

    // ═══════════════════════════════════════════════
    // Service
    // ═══════════════════════════════════════════════

    private final Map<String, Center> centers = new ConcurrentHashMap<>();
    // Quick lookup: "centerName|workoutType|startTime|endTime" → WorkoutSlot
    private final Map<String, WorkoutSlot> slotIndex = new ConcurrentHashMap<>();

    // ─── 1. Onboard Center ───

    public void onboardCenter(String centerName, List<String> centerTimings, List<String> workoutTypes) {
        List<TimeRange> timings = new ArrayList<>();
        for (String t : centerTimings) {
            String[] parts = t.split("-");
            timings.add(new TimeRange(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        }

        Set<String> types = new HashSet<>(workoutTypes);
        Center center = new Center(centerName, timings, types);

        // Atomic: only succeeds if key didn't exist
        if (centers.putIfAbsent(centerName, center) != null) {
            throw new IllegalArgumentException("Center already exists: " + centerName);
        }
        System.out.println("Onboarded: " + centerName);
    }

    // ─── 2. Add Workout Slot ───

    public boolean addWorkoutSlot(String centerName, String workoutType, int startTime, int endTime, int totalSeats) {
        Center center = centers.get(centerName);
        if (center == null) return false;
        if (startTime >= endTime || totalSeats <= 0) return false;
        if (!center.allowedWorkoutTypes.contains(workoutType)) return false;
        if (!center.isWithinTimings(startTime, endTime)) return false;

        // Synchronized on center: overlap check + add must be atomic
        // Prevents two admins creating overlapping slots concurrently
        synchronized (center) {
            if (center.hasOverlap(startTime, endTime)) return false;

            WorkoutSlot slot = new WorkoutSlot(centerName, workoutType, startTime, endTime, totalSeats);
            center.slots.add(slot);
            slotIndex.put(slot.getKey(), slot); // ConcurrentHashMap: atomic publish
        }
        return true;
    }

    // ─── 3. View Availability (Sorted by Start Time) ───

    public List<String> viewWorkoutAvailabilityByStartTime(String workoutType, String centerName) {
        List<WorkoutSlot> results = getFilteredSlots(workoutType, centerName);

        results.sort((a, b) -> {
            if (a.startTime != b.startTime) return Integer.compare(a.startTime, b.startTime);
            return a.toString().compareTo(b.toString());
        });

        return results.stream().map(WorkoutSlot::toString).collect(Collectors.toList());
    }

    // ─── 4. View Availability (Sorted by Seats Available) ───

    public List<String> viewWorkoutAvailabilityBySeatsAvailable(String workoutType, String centerName) {
        if (centerName == null || centerName.isEmpty() || centerName.equals("*")) {
            throw new IllegalArgumentException("centerName must be provided for this view");
        }

        List<WorkoutSlot> results = getFilteredSlots(workoutType, centerName);

        results.sort((a, b) -> {
            if (a.seatsAvailable != b.seatsAvailable) return Integer.compare(a.seatsAvailable, b.seatsAvailable);
            return a.toString().compareTo(b.toString());
        });

        return results.stream().map(WorkoutSlot::toString).collect(Collectors.toList());
    }

    // ─── 5. Book Session ───

    public String bookSession(String userId, String centerName, String workoutType, int startTime, int endTime) {
        String key = centerName + "|" + workoutType + "|" + startTime + "|" + endTime;
        WorkoutSlot slot = slotIndex.get(key);

        if (slot == null) return "SLOT_NOT_FOUND";

        slot.lock.lock();
        try {
            if (slot.bookedUsers.contains(userId)) return "ALREADY_BOOKED";
            if (slot.seatsAvailable <= 0) return "NO_SEATS";

            slot.bookedUsers.add(userId);
            slot.seatsAvailable--;
            return "BOOKED";
        } finally {
            slot.lock.unlock();
        }
    }

    // ─── 6. Cancel Session ───

    public String cancelSession(String userId, String centerName, String workoutType, int startTime, int endTime) {
        String key = centerName + "|" + workoutType + "|" + startTime + "|" + endTime;
        WorkoutSlot slot = slotIndex.get(key);

        if (slot == null) return "SLOT_NOT_FOUND";

        List<String> notifications = null;

        slot.lock.lock();
        try {
            if (!slot.bookedUsers.contains(userId)) return "BOOKING_NOT_FOUND";

            slot.bookedUsers.remove(userId);
            slot.seatsAvailable++;

            // Collect notifications inside lock (atomic with cancel)
            if (!slot.interestList.isEmpty()) {
                notifications = new ArrayList<>();
                for (String interestedUser : slot.interestList) {
                    notifications.add("NOTIFY|" + interestedUser + "|" + centerName + "|" + workoutType + "|" + startTime + "-" + endTime);
                }
                slot.interestList.clear();
            }
        } finally {
            slot.lock.unlock();
        }

        // Fire notifications OUTSIDE lock (avoid holding lock during I/O)
        if (notifications != null) {
            notifications.forEach(System.out::println);
        }

        return "CANCELLED";
    }

    // ─── 7. Add to Interest List (Notify-Me) ───

    public String addToInterestList(String userId, String centerName, String workoutType, int startTime, int endTime) {
        String key = centerName + "|" + workoutType + "|" + startTime + "|" + endTime;
        WorkoutSlot slot = slotIndex.get(key);

        if (slot == null) return "SLOT_NOT_FOUND";

        slot.lock.lock();
        try {
            if (slot.seatsAvailable > 0) return "SEATS_AVAILABLE";
            if (slot.interestList.contains(userId)) return "ALREADY_INTERESTED";

            slot.interestList.add(userId);
            return "INTEREST_ADDED";
        } finally {
            slot.lock.unlock();
        }
    }

    // ─── 8. Notify Interested Users ───

    public List<String> notifyInterestedUsers(String centerName, String workoutType, int startTime, int endTime) {
        String key = centerName + "|" + workoutType + "|" + startTime + "|" + endTime;
        WorkoutSlot slot = slotIndex.get(key);

        if (slot == null) return Collections.emptyList();

        List<String> notifications = new ArrayList<>();
        for (String userId : slot.interestList) {
            notifications.add("NOTIFY|" + userId + "|" + centerName + "|" + workoutType + "|" + startTime + "-" + endTime);
        }
        // Clear interest list after notification
        slot.interestList.clear();
        return notifications;
    }

    // ─── Helpers ───

    private List<WorkoutSlot> getFilteredSlots(String workoutType, String centerName) {
        List<WorkoutSlot> results = new ArrayList<>();

        boolean allCenters = (centerName == null || centerName.isEmpty() || centerName.equals("*"));

        if (allCenters) {
            for (Center center : centers.values()) {
                for (WorkoutSlot slot : center.slots) {
                    if (slot.workoutType.equals(workoutType)) {
                        results.add(slot);
                    }
                }
            }
        } else {
            Center center = centers.get(centerName);
            if (center != null) {
                for (WorkoutSlot slot : center.slots) {
                    if (slot.workoutType.equals(workoutType)) {
                        results.add(slot);
                    }
                }
            }
        }

        return results;
    }

    // ═══════════════════════════════════════════════
    // Demo — All examples from the problem
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        GetInShape platform = new GetInShape();

        // ─── Example 1: Center Onboarding + Slot Definition ───
        System.out.println("═══ Example 1: Onboarding + Slots ═══\n");

        platform.onboardCenter("connaught_place",
            Arrays.asList("6-9", "18-21"),
            Arrays.asList("weights", "cardio", "yoga", "swimming"));

        platform.onboardCenter("bandra_west",
            Arrays.asList("7-10", "19-22"),
            Arrays.asList("weights", "cardio", "yoga"));

        System.out.println("addSlot weights 6-7 @ CP: " +
            platform.addWorkoutSlot("connaught_place", "weights", 6, 7, 100));  // true
        System.out.println("addSlot cardio 7-8 @ CP: " +
            platform.addWorkoutSlot("connaught_place", "cardio", 7, 8, 150));   // true
        System.out.println("addSlot yoga 8-9 @ CP: " +
            platform.addWorkoutSlot("connaught_place", "yoga", 8, 9, 200));     // true

        System.out.println("addSlot weights 18-19 @ BW: " +
            platform.addWorkoutSlot("bandra_west", "weights", 18, 19, 100));    // false (outside timings)
        System.out.println("addSlot swimming 19-20 @ BW: " +
            platform.addWorkoutSlot("bandra_west", "swimming", 19, 20, 100));   // false (not allowed type)

        System.out.println("addSlot cardio 19-20 @ BW: " +
            platform.addWorkoutSlot("bandra_west", "cardio", 19, 20, 20));      // true
        System.out.println("addSlot weights 20-21 @ BW: " +
            platform.addWorkoutSlot("bandra_west", "weights", 20, 21, 100));    // true
        System.out.println("addSlot weights 21-22 @ BW: " +
            platform.addWorkoutSlot("bandra_west", "weights", 21, 22, 100));    // true

        // ─── Example 2: View by Start Time ───
        System.out.println("\n═══ Example 2: View by Start Time (weights, all centers) ═══\n");
        List<String> view1 = platform.viewWorkoutAvailabilityByStartTime("weights", "*");
        view1.forEach(s -> System.out.println("  " + s));

        // ─── Example 3: Book + Verify + Double-book ───
        System.out.println("\n═══ Example 3: Book Session ═══\n");
        System.out.println("Book vaibhav weights 6-7 CP: " +
            platform.bookSession("vaibhav", "connaught_place", "weights", 6, 7));  // BOOKED

        List<String> view2 = platform.viewWorkoutAvailabilityByStartTime("weights", "*");
        view2.forEach(s -> System.out.println("  " + s));  // 99 seats now

        System.out.println("Book vaibhav again: " +
            platform.bookSession("vaibhav", "connaught_place", "weights", 6, 7));  // ALREADY_BOOKED

        // ─── Example 4: View by Seats Available ───
        System.out.println("\n═══ Example 4: View by Seats Available (weights, bandra_west) ═══\n");
        List<String> view3 = platform.viewWorkoutAvailabilityBySeatsAvailable("weights", "bandra_west");
        view3.forEach(s -> System.out.println("  " + s));

        // ─── Example 5: Cancel + Notify-Me ───
        System.out.println("\n═══ Example 5: Cancel + Notify-Me ═══\n");

        // Setup: 1-seat yoga slot
        System.out.println("addSlot yoga 18-19 @ CP (1 seat): " +
            platform.addWorkoutSlot("connaught_place", "yoga", 18, 19, 1));  // true

        System.out.println("Book arjun: " +
            platform.bookSession("arjun", "connaught_place", "yoga", 18, 19));  // BOOKED

        System.out.println("Book rohit: " +
            platform.bookSession("rohit", "connaught_place", "yoga", 18, 19));  // NO_SEATS

        System.out.println("Rohit joins interest list: " +
            platform.addToInterestList("rohit", "connaught_place", "yoga", 18, 19));  // INTEREST_ADDED

        System.out.println("Cancel arjun: " +
            platform.cancelSession("arjun", "connaught_place", "yoga", 18, 19));  // CANCELLED

        // Notify interested users
        List<String> notifications = platform.notifyInterestedUsers("connaught_place", "yoga", 18, 19);
        notifications.forEach(n -> System.out.println("  " + n));  // NOTIFY|rohit|...

        // View yoga after cancel
        System.out.println("\nYoga slots at CP:");
        List<String> view4 = platform.viewWorkoutAvailabilityByStartTime("yoga", "connaught_place");
        view4.forEach(s -> System.out.println("  " + s));
    }
}
