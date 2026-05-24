# Parking Lot — Class Diagram

## High-Level Package Structure

```
┌────────────────────────────────────────────────────────────────────┐
│                          parkinglot                                 │
├─────────────┬──────────────┬──────────┬─────────┬─────────┬─────────┤
│   enums     │  exception   │ factory  │  gate   │ model   │ service │
│             │              │          │         │         │         │
│ SpotType    │ ParkingFull  │ Parking  │ Entry   │ Parking │ Ticket  │
│ VehicleType │ SpotOccupied │ Spot     │ Gate    │ Lot /   │ Service │
│ Ticket      │ Ticket...    │ Factory  │ Exit    │ Floor / │ Fee     │
│ Status      │ (5 types)    │          │ Gate    │ Spot /  │ Strategy│
│             │              │          │         │ Vehicle/│ Hourly  │
│             │              │          │         │ Ticket/ │ FeeCalc │
│             │              │          │         │ Payment │         │
└─────────────┴──────────────┴──────────┴─────────┴─────────┴─────────┘

                          strategy
                          ────────
                          SpotAssignmentStrategy (interface)
                          NearestFirstStrategy
```

---

## Domain Model — Spots (Inheritance + Factory)

```
                    ┌───────────────────────────────┐
                    │    «abstract» ParkingSpot      │
                    │───────────────────────────────│
                    │ - spotId: String               │
                    │ - floorNumber: int             │
                    │ - spotNumber: int              │
                    │ - spotType: SpotType           │
                    │ - isHandicap: boolean          │
                    │ - hasEVCharging: boolean       │
                    │ - vehicle: Vehicle             │
                    │ - occupied: boolean            │
                    │───────────────────────────────│
                    │ + canFit(VehicleType): bool ⟵ abstract │
                    │ + assignVehicle(Vehicle): void │
                    │ + removeVehicle(): Vehicle     │
                    │ + isAvailable(): boolean       │
                    │ + getSpotType(): SpotType      │
                    │ + getSpotNumber(): int         │
                    │ + getFloorNumber(): int        │
                    └───────────────┬───────────────┘
                                    │ extends
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
   ┌──────────────────┐   ┌────────────────┐   ┌──────────────────┐
   │ MotorcycleParking │   │ CarParkingSpot │   │ LargeParkingSpot │
   │ Spot              │   │                │   │                  │
   │──────────────────│   │────────────────│   │──────────────────│
   │ spotType = SMALL  │   │ spotType=MEDIUM│   │ spotType = LARGE │
   │──────────────────│   │────────────────│   │──────────────────│
   │ + canFit(): only  │   │ + canFit():    │   │ + canFit():      │
   │   MOTORCYCLE      │   │   MOTORCYCLE,  │   │   MOTORCYCLE,    │
   │                   │   │   CAR          │   │   CAR, TRUCK     │
   └──────────────────┘   └────────────────┘   └──────────────────┘
                                    ▲
                                    │ creates
                                    │
                    ┌───────────────────────────────┐
                    │   «factory» ParkingSpotFactory  │
                    │───────────────────────────────│
                    │ + createSpot(SpotType, id,     │
                    │     floor, num, handicap, ev): │
                    │     ParkingSpot   [static]    │
                    │───────────────────────────────│
                    │ switch(SpotType):              │
                    │   SMALL  → MotorcycleSpot      │
                    │   MEDIUM → CarSpot             │
                    │   LARGE  → LargeSpot           │
                    └───────────────────────────────┘
```

---

## Domain Model — Aggregates

```
┌──────────────────────────────────────────────────────────────────────┐
│                           ParkingLot                                  │
│──────────────────────────────────────────────────────────────────────│
│ - name: String                                                        │
│ - floors: List<ParkingFloor>                                          │
│ - totalCapacity: int                                                  │
│ - vehicleLocationMap: Map<String, ParkingSpot>    ← O(1) lookup      │
│──────────────────────────────────────────────────────────────────────│
│ + registerVehicleLocation(plate, spot): void                          │
│ + unregisterVehicleLocation(plate): void                              │
│ + findVehicle(plate): Optional<ParkingSpot>      ← O(1)              │
│ + getAvailableCount(VehicleType): int                                 │
│ + isFull(VehicleType): boolean                                        │
│ + getOccupancyRate(): OccupancyStats                                  │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ 1:N   (has many)
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          ParkingFloor                                 │
│──────────────────────────────────────────────────────────────────────│
│ - floorNumber: int                                                    │
│ - spots: List<ParkingSpot>                                            │
│ - floorLock: ReentrantLock    ← for atomic multi-spot assignment     │
│──────────────────────────────────────────────────────────────────────│
│ + getAvailableSpots(VehicleType): List<ParkingSpot>                   │
│ + getAvailableCount(VehicleType): int                                 │
│ + findConsecutiveSpots(count, vehicleType):                          │
│     Optional<List<ParkingSpot>>                                       │
│ + assignMultiSpot(Vehicle, VehicleType): List<ParkingSpot>           │
│ + findVehicle(plate): Optional<ParkingSpot>                          │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ 1:N   (has many)
                               ▼
                        (ParkingSpot hierarchy above)
```

---

## Ticket, Vehicle & Payment

```
┌────────────────────────┐       ┌────────────────────────┐
│      Vehicle            │       │       Ticket            │
│────────────────────────│       │────────────────────────│
│ - licensePlate: String  │       │ - ticketId: String      │
│ - type: VehicleType     │       │ - vehicle: Vehicle      │
│ - color: String         │       │ - spots: List<Spot>    │
└────────────────────────┘       │ - entryTime: Instant    │
          ▲                       │ - entryGateId: String   │
          │ 1                     │ - exitTime: Instant     │
          │                       │ - status: TicketStatus  │
          │                       │────────────────────────│
          │ 1                     │ + getDuration()         │
          │                       │ + getSpot() (primary)   │
┌────────────────────────┐       │ + getSpots()            │
│     VehicleType         │       │ + getSpotCount()        │
│ «enum»                  │       └──────────┬─────────────┘
│────────────────────────│                  │ 1:1
│ MOTORCYCLE (1 spot)     │                  ▼
│ CAR        (1 spot)     │       ┌────────────────────────┐
│ TRUCK      (1 spot)     │       │      Payment            │
│ BUS        (3 spots)    │       │────────────────────────│
│────────────────────────│       │ - paymentId: String     │
│ - requiredSpots: int    │       │ - ticketId: String      │
│ - compatibleSpotTypes:  │       │ - amount: double        │
│     Set<SpotType>       │       │ - duration: Duration    │
│ + isMultiSpot(): bool   │       │ - vehicleType:          │
│ + fitsIn(SpotType): bool│       │     VehicleType         │
│ + getRequiredSpots()    │       │ - paidAt: Instant       │
└────────────────────────┘       └────────────────────────┘
```

---

## Services & Strategy Pattern

```
┌──────────────────────────────────────────────────────────────┐
│                      TicketService                            │
│──────────────────────────────────────────────────────────────│
│ - activeTickets: Map<String, Ticket>                          │
│ - ticketsByPlate: Map<String, Ticket>                         │
│ - ticketCounter: AtomicLong                                   │
│──────────────────────────────────────────────────────────────│
│ + issueTicket(Vehicle, List<Spot>, gateId): Ticket            │
│ + closeTicket(ticketId): Ticket                               │
│ + getTicket(id): Ticket                                       │
│ + getTicketByPlate(plate): Ticket                             │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────┐    uses    ┌────────────────────────────┐
│   FeeCalculator       │──────────▶│   «interface» FeeStrategy   │
│──────────────────────│            │────────────────────────────│
│ - feeStrategy:       │            │ + calculateFee(Ticket):    │
│     FeeStrategy      │            │     double                 │
│ - lostTicketPenalty: │            └──────────────┬─────────────┘
│     double           │                           │ implements
│──────────────────────│                           ▼
│ + calculate(Ticket): │            ┌────────────────────────────┐
│     Payment          │            │   HourlyFeeStrategy         │
└──────────────────────┘            │────────────────────────────│
                                    │ - hourlyRates:             │
                                    │     Map<VehicleType,       │
                                    │         Double>            │
                                    │────────────────────────────│
                                    │ + calculateFee(Ticket):    │
                                    │     double                 │
                                    └────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│   «interface» SpotAssignmentStrategy                  │
│──────────────────────────────────────────────────────│
│ + findSpots(ParkingLot, VehicleType):                 │
│     Optional<List<ParkingSpot>>                       │
└──────────────────────────┬───────────────────────────┘
                           │ implements
                           ▼
┌──────────────────────────────────────────────────────┐
│   NearestFirstStrategy                                │
│──────────────────────────────────────────────────────│
│ + findSpots(lot, type): Optional<List<Spot>>          │
│ - findSingleSpot(lot, type)                          │
│ - findConsecutiveSpots(lot, type)                    │
│                                                       │
│ Strategy: lowest floor first, then lowest spot number │
└──────────────────────────────────────────────────────┘
```

---

## Gates (Controllers / Facade)

```
┌──────────────────────────────────────────────────────────────┐
│                        EntryGate                              │
│──────────────────────────────────────────────────────────────│
│ - gateId: String                                              │
│ - parkingLot: ParkingLot                                      │
│ - spotAssigner: SpotAssignmentStrategy                        │
│ - ticketService: TicketService                                │
│──────────────────────────────────────────────────────────────│
│ + processEntry(type, plate, color): Ticket                    │
│ - processSingleSpotEntry(vehicle, type): Ticket               │
│ - processMultiSpotEntry(vehicle, type): Ticket                │
│                                                               │
│ 1. Check if vehicle already parked                            │
│ 2. Build Vehicle                                              │
│ 3. Dispatch to single or multi-spot flow                      │
│ 4. Strategy finds spot(s) → assign → register in lot map     │
│ 5. Issue ticket                                               │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                        ExitGate                               │
│──────────────────────────────────────────────────────────────│
│ - gateId: String                                              │
│ - parkingLot: ParkingLot                                      │
│ - ticketService: TicketService                                │
│ - feeCalculator: FeeCalculator                                │
│──────────────────────────────────────────────────────────────│
│ + processExit(ticketId): Payment                              │
│                                                               │
│ 1. Close ticket (sets exit time)                              │
│ 2. Calculate fee via FeeCalculator                           │
│ 3. Unregister from vehicleLocationMap                        │
│ 4. Free all spot(s) on the ticket                            │
└──────────────────────────────────────────────────────────────┘
```

---

## Enums

```
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│   SpotType       │    │   VehicleType    │    │  TicketStatus    │
│   «enum»         │    │   «enum»         │    │   «enum»         │
│──────────────────│    │──────────────────│    │──────────────────│
│ SMALL            │    │ MOTORCYCLE       │    │ ACTIVE           │
│ MEDIUM           │    │ CAR              │    │ PAID             │
│ LARGE            │    │ TRUCK            │    │ LOST             │
└──────────────────┘    │ BUS              │    └──────────────────┘
                        │                  │
                        │ + requiredSpots  │
                        │ + compatibleSpots│
                        │ + isMultiSpot()  │
                        │ + fitsIn()       │
                        └──────────────────┘
```

---

## Exceptions

```
┌──────────────────────────────┐
│     RuntimeException          │
└───────────────┬──────────────┘
                │ extends
  ┌─────────────┼──────────────┬──────────────┬────────────────────┐
  ▼             ▼              ▼              ▼                    ▼
ParkingFull  SpotOccupied  TicketNot    TicketAlready      VehicleAlready
Exception    Exception     Found        Closed             Parked
                           Exception    Exception          Exception
```

---

## Complete Relationship Overview

```
                    ┌──────────────┐
                    │  EntryGate   │─────uses─────▶ SpotAssignmentStrategy
                    └──────┬───────┘                      │ impl
                           │ uses                         ▼
                           ▼                     NearestFirstStrategy
                    ┌──────────────┐
                    │  ParkingLot  │───contains──▶ ParkingFloor ───▶ ParkingSpot
                    └──────┬───────┘                                       ▲
                           │ vehicleLocationMap                            │ created by
                           │                                               │
                    ┌──────▼───────┐                                       │
                    │   ExitGate   │─────uses─────▶ FeeCalculator    ParkingSpotFactory
                    └──────┬───────┘                      │
                           │                              │ uses
                           │                              ▼
                           │                         FeeStrategy
                           │                              │ impl
                           │ uses                         ▼
                           ▼                      HourlyFeeStrategy
                    ┌──────────────┐
                    │ TicketService│────manages───▶  Ticket ─────▶ Vehicle
                    └──────────────┘                   │
                                                       ▼
                                                     Payment
```

---

## Design Patterns Used

| Pattern              | Where                                    | Why                                   |
|----------------------|------------------------------------------|---------------------------------------|
| Strategy             | SpotAssignmentStrategy, FeeStrategy      | Swap algorithms without changing code |
| Factory              | ParkingSpotFactory                       | Decouple creation from usage          |
| Template Method      | ParkingSpot (abstract canFit)            | Subclass defines compatibility        |
| Facade               | EntryGate, ExitGate                      | Hide complex flows from callers       |
| Singleton (implicit) | TicketService (one per lot)              | Shared ticket registry                |
| Observer (optional)  | Could be added for real-time dashboards  | Notify occupancy changes              |
