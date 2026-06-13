# GetInShape — Fitness Booking Platform

## Problem Statement

Build an in-memory backend for a fitness booking platform. Gym centers are onboarded with operating timings and supported workout types. Admins define daily workout slots, and customers can view, book, and cancel sessions.

## APIs

| # | Method | Description |
|---|--------|-------------|
| 1 | `onboardCenter(name, timings, workoutTypes)` | Register a center |
| 2 | `addWorkoutSlot(center, type, start, end, seats)` | Admin defines a slot |
| 3 | `viewWorkoutAvailabilityByStartTime(type, center)` | View slots sorted by start time |
| 4 | `viewWorkoutAvailabilityBySeatsAvailable(type, center)` | View slots sorted by seats |
| 5 | `bookSession(userId, center, type, start, end)` | Book a seat |
| 6 | `cancelSession(userId, center, type, start, end)` | Cancel a booking |
| 7 | `addToInterestList(userId, center, type, start, end)` | Notify-me when seat frees up |
| 8 | `notifyInterestedUsers(center, type, start, end)` | Fire notifications |

## Validations (addWorkoutSlot)

- Slot must lie within center's onboarded timings
- Workout type must be in center's allowed list
- No time overlap with existing slots at the same center
- `startTime < endTime` and `totalSeats > 0`

## Return Values

### bookSession
- `BOOKED` — success
- `NO_SEATS` — slot full
- `SLOT_NOT_FOUND` — invalid slot
- `ALREADY_BOOKED` — duplicate booking by same user

### cancelSession
- `CANCELLED` — success, seat restored
- `BOOKING_NOT_FOUND` — user has no booking
- `SLOT_NOT_FOUND` — invalid slot

### addToInterestList
- `INTEREST_ADDED` — user added to waitlist
- `ALREADY_INTERESTED` — duplicate
- `SEATS_AVAILABLE` — slot not full, no need for interest
- `SLOT_NOT_FOUND` — invalid slot

## Design Decisions

| Decision | Reasoning |
|----------|-----------|
| Slot key: `center|type|start|end` | O(1) lookup via HashMap |
| Overlap check: linear scan per center | N is small (daily slots ~10-20 per center) |
| Observer pattern for notify-me | Interest list per slot, cleared after notification |
| `Set<String> bookedUsers` per slot | O(1) duplicate check |
| Workout types as strings | Extensible — new types don't require code changes |

## Data Model

```
Center
├── name (unique identifier)
├── timings: List<TimeRange>        — operating windows
├── allowedWorkoutTypes: Set<String> — extensible
└── slots: List<WorkoutSlot>

WorkoutSlot
├── centerName, workoutType, startTime, endTime
├── totalSeats, seatsAvailable
├── bookedUsers: Set<String>        — who booked
└── interestList: List<String>      — notify-me queue
```

## Example Flow

```
1. Onboard "connaught_place" with timings [6-9, 18-21] and types [weights, cardio, yoga, swimming]
2. Admin adds: weights 6-7 (100 seats), cardio 7-8 (150 seats), yoga 8-9 (200 seats)
3. User books weights 6-7 → BOOKED (seats: 100 → 99)
4. Same user books again → ALREADY_BOOKED
5. Admin adds yoga 18-19 (1 seat) → user A books → BOOKED
6. User B tries → NO_SEATS → joins interest list
7. User A cancels → CANCELLED → User B notified
```

## Extensibility

- **Multi-day support**: Add a `date` field to slot key
- **New workout types**: Just pass new strings in `workoutTypes` list
- **Multiple slots at same time**: Remove overlap constraint (future scope)
- **Capacity management**: Add waitlist auto-booking on cancellation
- **Concurrency**: Add `synchronized` on bookSession/cancelSession or use ConcurrentHashMap
