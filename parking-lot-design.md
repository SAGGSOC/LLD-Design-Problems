# Parking Lot — Low-Level Design

---

## 1. Requirements

### Functional Requirements

- `enterParking(vehicleType, licensePlate) → ticket` — vehicle enters, gets a ticket with assigned spot
- `exitParking(ticketId) → payment` — vehicle exits, fee calculated based on duration
- `getAvailableSpots(vehicleType?) → count` — check availability by type
- `findVehicle(licensePlate) → {floor, spot}` — locate a parked vehicle
- `getOccupancyRate() → {total, occupied, available}` — dashboard for lot operator
- `reserveSpot(vehicleType, duration) → reservationId` — pre-book a spot (optional)

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| How many floors? | Multi-floor (configurable, default 5) |
| Vehicle types? | MOTORCYCLE, CAR, TRUCK (different spot sizes) |
| Pricing model? | Per-hour, different rates per vehicle type |
| Multiple entry/exit gates? | Yes — concurrent entry/exit |
| Payment methods? | Out of scope — just calculate fee |
| EV charging spots? | Yes — as a spot attribute |
| Handicap spots? | Yes — as a spot attribute |
| Spot assignment strategy? | Nearest available (lowest floor, closest to entrance) |

---

## 2. Class Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ParkingLotSystem                                    │
│─────────────────────────────────────────────────────────────────────────────│
│ - parkingLot: ParkingLot                                                    │
│ - ticketService: TicketService                                              │
│ - feeCalculator: FeeCalculator                                              │
│ - spotAssigner: SpotAssignmentStrategy                                      │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┐       ┌──────────────────────────────┐
│       ParkingLot             │       │      TicketService           │
│──────────────────────────────│       │──────────────────────────────│
│ - name: String               │       │ - activeTickets: Map<String, │
│ - floors: List<ParkingFloor> │       │     Ticket>                  │
│ - totalCapacity: int         │       │──────────────────────────────│
│──────────────────────────────│       │ + issueTicket(vehicle,       │
│ + getFloor(num): ParkingFloor│       │     spot): Ticket            │
│ + getAvailableCount(type):   │       │ + closeTicket(ticketId):     │
│     int                      │       │     Payment                  │
│ + getOccupancyRate(): Stats  │       │ + getTicket(id): Ticket      │
│ + findVehicle(plate):        │       └──────────────────────────────┘
│     SpotLocation             │
└──────────────────────────────┘       ┌──────────────────────────────┐
                                       │    FeeCalculator             │
┌──────────────────────────────┐       │──────────────────────────────│
│       ParkingFloor           │       │ - rates: Map<VehicleType,    │
│──────────────────────────────│       │     double>                  │
│ - floorNumber: int           │       │──────────────────────────────│
│ - spots: List<ParkingSpot>   │       │ + calculate(ticket): double  │
│──────────────────────────────│       │ + getRate(type): double      │
│ + getAvailableSpots(type):   │       └──────────────────────────────┘
│     List<ParkingSpot>        │
│ + getSpot(spotId): Spot      │
└──────────────────────────────┘

┌──────────────────────────────┐       ┌──────────────────────────────┐
│       ParkingSpot            │       │      EntryGate               │
│──────────────────────────────│       │──────────────────────────────│
│ - spotId: String             │       │ - gateId: String             │
│ - floorNumber: int           │       │ - spotAssigner: Strategy     │
│ - spotNumber: int            │       │ - ticketService: TicketSvc   │
│ - type: SpotType             │       │──────────────────────────────│
│ - isOccupied: boolean        │       │ + processEntry(vehicle):     │
│ - vehicle: Vehicle           │       │     Ticket                   │
│ - isHandicap: boolean        │       └──────────────────────────────┘
│ - hasEVCharging: boolean     │
│──────────────────────────────│       ┌──────────────────────────────┐
│ + assignVehicle(v): void     │       │      ExitGate                │
│ + removeVehicle(): Vehicle   │       │──────────────────────────────│
│ + isAvailable(): boolean     │       │ - gateId: String             │
│ + canFit(type): boolean      │       │ - ticketService: TicketSvc   │
└──────────────────────────────┘       │ - feeCalculator: FeeCalc     │
                                       │──────────────────────────────│
                                       │ + processExit(ticketId):     │
                                       │     Payment                  │
                                       └──────────────────────────────┘
```

### Entity Classes

```
┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│      Vehicle          │    │       Ticket          │    │      Payment          │
│──────────────────────│    │──────────────────────│    │──────────────────────│
│ - licensePlate: String│    │ - ticketId: String    │    │ - paymentId: String   │
│ - type: VehicleType   │    │ - vehicle: Vehicle    │    │ - ticketId: String    │
│ - color: String       │    │ - spot: ParkingSpot   │    │ - amount: double      │
│ - entryTime: Instant  │    │ - entryTime: Instant  │    │ - duration: Duration  │
└──────────────────────┘    │ - exitTime: Instant   │    │ - vehicleType: Type   │
                            │ - status: TicketStatus│    │ - paidAt: Instant     │
                            │ - gateId: String      │    └──────────────────────┘
                            └──────────────────────┘
```

---

## 3. Enums & State Machines

```java
public enum VehicleType   { MOTORCYCLE, CAR, TRUCK }
public enum SpotType      { SMALL, MEDIUM, LARGE }   // SMALL=bike, MEDIUM=car, LARGE=truck
public enum TicketStatus  { ACTIVE, PAID, LOST }
```

### Vehicle → Spot Type Mapping

```
┌───────────────┬────────────────┬──────────────────────────────┐
│ VehicleType   │ Fits In        │ Logic                        │
├───────────────┼────────────────┼──────────────────────────────┤
│ MOTORCYCLE    │ SMALL, MEDIUM, │ Can fit in any spot          │
│               │ LARGE          │                              │
│ CAR           │ MEDIUM, LARGE  │ Needs at least MEDIUM        │
│ TRUCK         │ LARGE only     │ Only fits in LARGE           │
└───────────────┴────────────────┴──────────────────────────────┘
```

### Ticket State Machine

```
┌────────┐     processExit()     ┌──────┐
│ ACTIVE │──────────────────────▶│ PAID │
└────────┘                       └──────┘
     │
     │  reportLost()
     ▼
┌──────┐
│ LOST │ → flat penalty fee
└──────┘
```

---

## 4. Spot Assignment Strategy (Strategy Pattern)

```
┌──────────────────────────────────────┐
│  «interface» SpotAssignmentStrategy  │
│──────────────────────────────────────│
│ + findSpot(lot, vehicleType):        │
│     ParkingSpot                      │
└──────────────────┬───────────────────┘
                   │
      ┌────────────┼────────────────┐
      ▼            ▼                ▼
┌───────────┐ ┌────────────┐ ┌──────────────┐
│ Nearest   │ │ Spread     │ │ Floor        │
│ First     │ │ Evenly     │ │ Priority     │
│           │ │            │ │              │
│ Lowest    │ │ Balance    │ │ Fill floor 1 │
│ floor,    │ │ across all │ │ first, then  │
│ lowest    │ │ floors     │ │ floor 2, etc │
│ spot num  │ │            │ │              │
└───────────┘ └────────────┘ └──────────────┘
```

---

## 5. Core Java Implementation

### 5.1 Vehicle & Spot

```java
public class Vehicle {
    private final String licensePlate;
    private final VehicleType type;
    private final String color;

    public Vehicle(String licensePlate, VehicleType type, String color) {
        this.licensePlate = licensePlate;
        this.type = type;
        this.color = color;
    }

    public String getLicensePlate() { return licensePlate; }
    public VehicleType getType()    { return type; }
}
```

```java
public class ParkingSpot {
    private final String spotId;
    private final int floorNumber;
    private final int spotNumber;
    private final SpotType type;
    private final boolean isHandicap;
    private final boolean hasEVCharging;
    private Vehicle vehicle;
    private boolean occupied;

    public ParkingSpot(String spotId, int floor, int spotNum,
                       SpotType type, boolean handicap, boolean ev) {
        this.spotId = spotId;
        this.floorNumber = floor;
        this.spotNumber = spotNum;
        this.type = type;
        this.isHandicap = handicap;
        this.hasEVCharging = ev;
        this.occupied = false;
    }

    public boolean canFit(VehicleType vehicleType) {
        if (occupied) return false;
        switch (vehicleType) {
            case MOTORCYCLE: return true;                          // fits anywhere
            case CAR:        return type == SpotType.MEDIUM || type == SpotType.LARGE;
            case TRUCK:      return type == SpotType.LARGE;
            default:         return false;
        }
    }

    public synchronized void assignVehicle(Vehicle v) {
        if (occupied) throw new SpotOccupiedException(spotId);
        this.vehicle = v;
        this.occupied = true;
    }

    public synchronized Vehicle removeVehicle() {
        Vehicle v = this.vehicle;
        this.vehicle = null;
        this.occupied = false;
        return v;
    }

    public boolean isAvailable()    { return !occupied; }
    public String getSpotId()       { return spotId; }
    public int getFloorNumber()     { return floorNumber; }
    public int getSpotNumber()      { return spotNumber; }
    public SpotType getType()       { return type; }
    public Vehicle getVehicle()     { return vehicle; }
}
```

### 5.2 ParkingFloor & ParkingLot

```java
public class ParkingFloor {
    private final int floorNumber;
    private final List<ParkingSpot> spots;

    public ParkingFloor(int floorNumber, List<ParkingSpot> spots) {
        this.floorNumber = floorNumber;
        this.spots = spots;
    }

    public List<ParkingSpot> getAvailableSpots(VehicleType type) {
        return spots.stream()
            .filter(s -> s.canFit(type))
            .collect(Collectors.toList());
    }

    public int getAvailableCount(VehicleType type) {
        return (int) spots.stream().filter(s -> s.canFit(type)).count();
    }

    public Optional<ParkingSpot> findVehicle(String licensePlate) {
        return spots.stream()
            .filter(s -> s.getVehicle() != null
                      && s.getVehicle().getLicensePlate().equals(licensePlate))
            .findFirst();
    }

    public int getFloorNumber()       { return floorNumber; }
    public List<ParkingSpot> getSpots() { return spots; }
}
```

```java
public class ParkingLot {
    private final String name;
    private final List<ParkingFloor> floors;
    private final int totalCapacity;

    public ParkingLot(String name, List<ParkingFloor> floors) {
        this.name = name;
        this.floors = floors;
        this.totalCapacity = floors.stream()
            .mapToInt(f -> f.getSpots().size()).sum();
    }

    public int getAvailableCount(VehicleType type) {
        return floors.stream()
            .mapToInt(f -> f.getAvailableCount(type)).sum();
    }

    public boolean isFull(VehicleType type) {
        return getAvailableCount(type) == 0;
    }

    public Optional<ParkingSpot> findVehicle(String licensePlate) {
        for (ParkingFloor floor : floors) {
            Optional<ParkingSpot> spot = floor.findVehicle(licensePlate);
            if (spot.isPresent()) return spot;
        }
        return Optional.empty();
    }

    public OccupancyStats getOccupancyRate() {
        int occupied = (int) floors.stream()
            .flatMap(f -> f.getSpots().stream())
            .filter(s -> !s.isAvailable()).count();
        return new OccupancyStats(totalCapacity, occupied, totalCapacity - occupied);
    }

    public List<ParkingFloor> getFloors() { return floors; }
}
```

### 5.3 Spot Assignment — Nearest First Strategy

```java
public interface SpotAssignmentStrategy {
    Optional<ParkingSpot> findSpot(ParkingLot lot, VehicleType vehicleType);
}

public class NearestFirstStrategy implements SpotAssignmentStrategy {

    // Lowest floor first, then lowest spot number — closest to entrance
    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, VehicleType vehicleType) {
        for (ParkingFloor floor : lot.getFloors()) {
            Optional<ParkingSpot> spot = floor.getAvailableSpots(vehicleType).stream()
                .min(Comparator.comparingInt(ParkingSpot::getSpotNumber));
            if (spot.isPresent()) return spot;
        }
        return Optional.empty();
    }
}
```

### 5.4 Ticket & TicketService

```java
public class Ticket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final Instant entryTime;
    private final String entryGateId;
    private Instant exitTime;
    private TicketStatus status;

    public Ticket(String ticketId, Vehicle vehicle, ParkingSpot spot,
                  Instant entryTime, String gateId) {
        this.ticketId = ticketId;
        this.vehicle = vehicle;
        this.spot = spot;
        this.entryTime = entryTime;
        this.entryGateId = gateId;
        this.status = TicketStatus.ACTIVE;
    }

    public Duration getDuration() {
        Instant end = exitTime != null ? exitTime : Instant.now();
        return Duration.between(entryTime, end);
    }

    // getters, setters
}
```

```java
public class TicketService {
    private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();
    private final Map<String, Ticket> ticketsByPlate = new ConcurrentHashMap<>();

    public Ticket issueTicket(Vehicle vehicle, ParkingSpot spot, String gateId) {
        String ticketId = "TKT-" + System.currentTimeMillis();
        Ticket ticket = new Ticket(ticketId, vehicle, spot, Instant.now(), gateId);
        activeTickets.put(ticketId, ticket);
        ticketsByPlate.put(vehicle.getLicensePlate(), ticket);
        return ticket;
    }

    public Ticket closeTicket(String ticketId) {
        Ticket ticket = activeTickets.get(ticketId);
        if (ticket == null) throw new TicketNotFoundException(ticketId);
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw new TicketAlreadyClosedException(ticketId);
        }

        ticket.setExitTime(Instant.now());
        ticket.setStatus(TicketStatus.PAID);
        activeTickets.remove(ticketId);
        ticketsByPlate.remove(ticket.getVehicle().getLicensePlate());
        return ticket;
    }

    public Ticket getTicket(String ticketId) {
        return activeTickets.get(ticketId);
    }

    public Ticket getTicketByPlate(String licensePlate) {
        return ticketsByPlate.get(licensePlate);
    }
}
```

### 5.5 Fee Calculator

```java
public class FeeCalculator {
    private final Map<VehicleType, Double> hourlyRates;
    private final double lostTicketPenalty;

    public FeeCalculator(Map<VehicleType, Double> rates, double lostPenalty) {
        this.hourlyRates = rates;
        this.lostTicketPenalty = lostPenalty;
    }

    // Default rates
    public FeeCalculator() {
        this.hourlyRates = Map.of(
            VehicleType.MOTORCYCLE, 2.0,
            VehicleType.CAR,        5.0,
            VehicleType.TRUCK,      10.0
        );
        this.lostTicketPenalty = 50.0;
    }

    public Payment calculate(Ticket ticket) {
        if (ticket.getStatus() == TicketStatus.LOST) {
            return new Payment(ticket.getTicketId(), lostTicketPenalty,
                               ticket.getDuration(), ticket.getVehicle().getType());
        }

        Duration duration = ticket.getDuration();
        long hours = duration.toHours();
        if (duration.toMinutesPart() > 0) hours++;  // round up partial hour

        double rate = hourlyRates.getOrDefault(ticket.getVehicle().getType(), 5.0);
        double amount = hours * rate;

        return new Payment(ticket.getTicketId(), amount, duration,
                           ticket.getVehicle().getType());
    }
}
```

```java
public class Payment {
    private final String paymentId;
    private final String ticketId;
    private final double amount;
    private final Duration duration;
    private final VehicleType vehicleType;
    private final Instant paidAt;

    public Payment(String ticketId, double amount, Duration duration,
                   VehicleType vehicleType) {
        this.paymentId = "PAY-" + System.currentTimeMillis();
        this.ticketId = ticketId;
        this.amount = amount;
        this.duration = duration;
        this.vehicleType = vehicleType;
        this.paidAt = Instant.now();
    }

    // getters
}
```

### 5.6 Entry & Exit Gates

```java
public class EntryGate {
    private final String gateId;
    private final ParkingLot lot;
    private final SpotAssignmentStrategy assigner;
    private final TicketService ticketService;

    public EntryGate(String gateId, ParkingLot lot,
                     SpotAssignmentStrategy assigner, TicketService ticketService) {
        this.gateId = gateId;
        this.lot = lot;
        this.assigner = assigner;
        this.ticketService = ticketService;
    }

    public synchronized Ticket processEntry(VehicleType type, String licensePlate,
                                             String color) {
        // 1. Check if vehicle already parked
        if (ticketService.getTicketByPlate(licensePlate) != null) {
            throw new VehicleAlreadyParkedException(licensePlate);
        }

        // 2. Find available spot
        Optional<ParkingSpot> spot = assigner.findSpot(lot, type);
        if (spot.isEmpty()) {
            throw new ParkingFullException("No spots available for " + type);
        }

        // 3. Assign vehicle to spot
        Vehicle vehicle = new Vehicle(licensePlate, type, color);
        spot.get().assignVehicle(vehicle);

        // 4. Issue ticket
        return ticketService.issueTicket(vehicle, spot.get(), gateId);
    }
}
```

```java
public class ExitGate {
    private final String gateId;
    private final TicketService ticketService;
    private final FeeCalculator feeCalculator;

    public ExitGate(String gateId, TicketService ticketService,
                    FeeCalculator feeCalculator) {
        this.gateId = gateId;
        this.ticketService = ticketService;
        this.feeCalculator = feeCalculator;
    }

    public Payment processExit(String ticketId) {
        // 1. Close ticket (sets exit time)
        Ticket ticket = ticketService.closeTicket(ticketId);

        // 2. Calculate fee
        Payment payment = feeCalculator.calculate(ticket);

        // 3. Free the spot
        ticket.getSpot().removeVehicle();

        return payment;
    }
}
```

---

## 6. Flow Diagrams

### Flow 1: Vehicle Entry

```
Vehicle arrives at entry gate
     │
     ▼
┌──────────────────────────┐
│ EntryGate.processEntry() │
│                          │
│ 1. Check: is vehicle     │
│    already parked?       │
│    (ticketsByPlate map)  │
│                          │
│    If yes → reject       │
│    "Vehicle already      │
│     parked"              │
└────────┬─────────────────┘
         │ No
         ▼
┌──────────────────────────┐
│ 2. Find spot:            │
│    NearestFirstStrategy  │
│                          │
│    Floor 1 → any spot    │
│    that canFit(type)?    │
│      Yes → pick lowest   │
│            spot number   │
│      No  → try Floor 2   │
│            ...           │
│                          │
│    No spot on any floor? │
│    → ParkingFullException│
└────────┬─────────────────┘
         │ Spot found
         ▼
┌──────────────────────────┐
│ 3. Assign vehicle to spot│
│    spot.assignVehicle(v) │
│    (synchronized)        │
│                          │
│ 4. Issue ticket:         │
│    ticketId = TKT-...    │
│    entryTime = now       │
│    status = ACTIVE       │
│                          │
│ 5. Display to driver:    │
│    "Floor 2, Spot 14"    │
│    "Ticket: TKT-123"    │
└──────────────────────────┘
```

### Flow 2: Vehicle Exit

```
Vehicle arrives at exit gate with ticket
     │
     ▼
┌──────────────────────────┐
│ ExitGate.processExit()   │
│                          │
│ 1. Lookup ticket:        │
│    ticketService          │
│    .closeTicket(ticketId)│
│                          │
│    Not found → error     │
│    Already paid → error  │
│                          │
│ 2. Set exitTime = now    │
│    status = PAID         │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ 3. Calculate fee:        │
│                          │
│    duration = exitTime   │
│              - entryTime │
│    = 3 hours 20 min      │
│    → rounds up to 4 hrs  │
│                          │
│    rate(CAR) = $5/hr     │
│    fee = 4 × $5 = $20   │
│                          │
│ 4. Free the spot:        │
│    spot.removeVehicle()  │
│    → spot now available  │
│                          │
│ 5. Display:              │
│    "Duration: 3h 20m"    │
│    "Amount: $20.00"      │
│    "Gate opens"          │
└──────────────────────────┘
```

### Flow 3: Find My Vehicle

```
User at kiosk enters license plate
     │
     ▼
┌──────────────────────────┐
│ parkingLot.findVehicle   │
│   ("ABC-1234")           │
│                          │
│ Scan each floor:         │
│   Floor 1 → not found   │
│   Floor 2 → Spot 14     │
│     has vehicle with     │
│     plate "ABC-1234"     │
│                          │
│ Return:                  │
│   "Floor 2, Spot 14"    │
│   "Vehicle: Blue CAR"   │
└──────────────────────────┘

Optimization: ticketsByPlate map gives O(1) lookup
  → ticket.getSpot() → floor + spot number
  → No need to scan all floors
```

---

## 7. Concurrency Handling

```
Problem: Two entry gates try to assign the same spot simultaneously.

Gate A: processEntry(CAR, "ABC-123")     Gate B: processEntry(CAR, "XYZ-789")
     │                                        │
     ▼                                        ▼
  findSpot → Floor 1, Spot 5              findSpot → Floor 1, Spot 5
     │                                        │
     ▼                                        ▼
  spot.assignVehicle(A)                    spot.assignVehicle(B)
  (synchronized)                           (synchronized)
     │                                        │
     ▼                                        ▼
  SUCCESS — A gets Spot 5                  BLOCKED — waits for lock
                                               │
                                               ▼
                                           spot.isOccupied = true
                                           → SpotOccupiedException
                                           → Retry: find next spot
                                           → Floor 1, Spot 6 → SUCCESS

Solution: synchronized on ParkingSpot.assignVehicle()
  + retry logic in EntryGate if spot was taken between find and assign.

For higher throughput: use CAS (Compare-And-Swap) with AtomicBoolean
  instead of synchronized blocks.
```

```java
// Alternative: Lock-free with AtomicBoolean
public class ParkingSpot {
    private final AtomicBoolean occupied = new AtomicBoolean(false);
    private volatile Vehicle vehicle;

    public boolean tryAssign(Vehicle v) {
        if (occupied.compareAndSet(false, true)) {
            this.vehicle = v;
            return true;   // success
        }
        return false;      // someone else got it — caller retries
    }

    public Vehicle removeVehicle() {
        Vehicle v = this.vehicle;
        this.vehicle = null;
        occupied.set(false);
        return v;
    }
}
```

---

## 8. Fee Calculation Examples

```
┌───────────────┬──────────┬──────────┬──────────┐
│ Vehicle       │ Duration │ Rate     │ Fee      │
├───────────────┼──────────┼──────────┼──────────┤
│ MOTORCYCLE    │ 2h 00m   │ $2/hr    │ $4.00    │
│ CAR           │ 3h 20m   │ $5/hr    │ $20.00   │  (rounds up to 4h)
│ CAR           │ 0h 45m   │ $5/hr    │ $5.00    │  (minimum 1 hour)
│ TRUCK         │ 5h 01m   │ $10/hr   │ $60.00   │  (rounds up to 6h)
│ CAR (lost)    │ —        │ flat     │ $50.00   │  (lost ticket penalty)
└───────────────┴──────────┴──────────┴──────────┘
```

---

## 9. Parking Lot Initialization Example

```java
public class ParkingLotBuilder {

    public static ParkingLot buildDefaultLot() {
        List<ParkingFloor> floors = new ArrayList<>();

        for (int f = 1; f <= 5; f++) {
            List<ParkingSpot> spots = new ArrayList<>();
            int spotNum = 1;

            // 10 SMALL spots (motorcycles)
            for (int i = 0; i < 10; i++) {
                spots.add(new ParkingSpot(
                    "F" + f + "-S" + spotNum, f, spotNum++,
                    SpotType.SMALL, false, false));
            }

            // 30 MEDIUM spots (cars) — 2 handicap, 3 EV
            for (int i = 0; i < 30; i++) {
                boolean handicap = (i < 2);
                boolean ev = (i >= 2 && i < 5);
                spots.add(new ParkingSpot(
                    "F" + f + "-S" + spotNum, f, spotNum++,
                    SpotType.MEDIUM, handicap, ev));
            }

            // 10 LARGE spots (trucks/SUVs)
            for (int i = 0; i < 10; i++) {
                spots.add(new ParkingSpot(
                    "F" + f + "-S" + spotNum, f, spotNum++,
                    SpotType.LARGE, false, false));
            }

            floors.add(new ParkingFloor(f, spots));
        }

        return new ParkingLot("Downtown Parking", floors);
        // Total: 5 floors × 50 spots = 250 spots
        // 50 SMALL + 150 MEDIUM (10 handicap, 15 EV) + 50 LARGE
    }
}
```

---

## 10. Design Decisions & Trade-offs

| Decision | Trade-off |
|---|---|
| Strategy pattern for spot assignment | + Easy to swap algorithms (nearest, spread, priority). - Slight indirection overhead. |
| `synchronized` on spot assignment | + Simple, correct. - Contention under high concurrency. CAS is faster but more complex. |
| In-memory maps for tickets | + O(1) lookup by ticketId and licensePlate. - Lost on restart (need persistence for production). |
| Round-up partial hours | + Simple billing. - Customer pays for unused time. Alternative: per-minute billing. |
| Vehicle type → spot type mapping | + Motorcycles can use any spot (flexible). - Wastes large spots on small vehicles. Could add "best fit" preference. |
| Separate Entry/Exit gate classes | + Clear separation of concerns. - Need shared state (TicketService) between them. |

---

## 11. End-to-End Scenario

```
8:00 AM  Lot opens. 250 spots available across 5 floors.

8:15 AM  Car "ABC-1234" enters Gate A
         → NearestFirst: Floor 1, Spot 11 (first MEDIUM)
         → Ticket TKT-001 issued, status=ACTIVE

8:20 AM  Truck "TRK-5678" enters Gate B
         → NearestFirst: Floor 1, Spot 41 (first LARGE)
         → Ticket TKT-002 issued

8:30 AM  Motorcycle "BIKE-99" enters Gate A
         → NearestFirst: Floor 1, Spot 1 (first SMALL)
         → Ticket TKT-003 issued

9:00 AM  Two cars arrive simultaneously at Gate A and Gate B
         → Both find Floor 1, Spot 12
         → Gate A locks spot first → success
         → Gate B gets SpotOccupiedException → retries → Floor 1, Spot 13

11:30 AM Car "ABC-1234" exits Gate C with TKT-001
         → Duration: 3h 15m → rounds to 4h
         → Fee: 4 × $5 = $20.00
         → Spot 11 freed → available for next car

12:00 PM User at kiosk: "Where's TRK-5678?"
         → ticketsByPlate lookup → TKT-002 → Floor 1, Spot 41

2:00 PM  Truck "TRK-5678" lost ticket
         → reportLost(TKT-002) → status=LOST
         → Fee: $50.00 flat penalty
         → Spot 41 freed

6:00 PM  Lot occupancy: 180/250 = 72%
         → Dashboard shows per-floor breakdown
```
