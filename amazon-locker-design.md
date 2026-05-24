# Amazon Locker System — Low-Level Design

---

## 1. Requirements

### Functional Requirements

- `reserveLocker(packageDimensions, locationId, customerId) → reservationId` — system reserves a locker for an incoming package
- `depositPackage(reservationId, deliveryAgentId) → confirmation` — delivery agent drops off package, locker is locked, OTP generated
- `retrievePackage(lockerId, otp) → package` — customer enters OTP at kiosk, locker unlocks
- `returnPackage(lockerId, otp) → returnId` — customer returns a package using the same locker (reverse flow)
- `findAvailableLocker(size, locationId) → lockerId` — internal: find a free locker of appropriate size
- `getLockerStatus(lockerId) → status` — check if locker is empty, occupied, maintenance
- `listNearbyLocations(lat, lng, radius) → locations[]` — customer finds nearby locker pickup points
- `handleExpiry(reservationId) → confirmation` — package uncollected past deadline → notify, escalate
- `reportIssue(lockerId, issueType) → ticketId` — customer or tech reports malfunction

### Non-Functional Requirements

| Requirement | Target |
|---|---|
| Locker locations | 10K+ globally |
| Lockers per location | 20-100 |
| Daily package deposits | 1M+ |
| OTP verification latency | < 200ms |
| Availability | 99.99% (can't lock people out of their packages) |
| OTP security | Cryptographically secure, time-bound |
| Holding period | 3 days default, configurable |
| Package size compatibility | Small, Medium, Large, XL |

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| Package sizes? | SMALL, MEDIUM, LARGE, XL (matching locker sizes) |
| OTP format? | 6-digit numeric, valid for 3 days |
| Multiple packages per reservation? | No — one package per locker slot |
| Returns? | Yes — same locker system supports returns |
| Refrigerated lockers? | Out of scope (but extensible) |
| Locker types? | Physical sizes: SMALL, MEDIUM, LARGE, XL |
| Payment? | No payment at locker (already paid at checkout) |
| Notifications? | SMS/Email with OTP when package is deposited |
| What if OTP is wrong 3x? | Lock reservation, require customer service |

---

## 2. Domain Overview

```
Customer orders on Amazon → selects Locker delivery
     │
     ▼
System reserves a locker based on package size + location
     │
     ▼
Delivery agent arrives → scans package → locker unlocks
     │
     ▼
Agent deposits → closes door → locker locks
     │
     ▼
System generates 6-digit OTP → SMS + Email to customer
     │
     ▼
Customer visits location → enters OTP at kiosk
     │
     ▼
Matching locker unlocks → customer retrieves → closes door
     │
     ▼
Locker marked AVAILABLE → reservation COMPLETED
```

---

## 3. Class Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         AmazonLockerSystem                                   │
│─────────────────────────────────────────────────────────────────────────────│
│ - locationService: LockerLocationService                                    │
│ - reservationService: ReservationService                                    │
│ - depositService: DepositService                                            │
│ - retrievalService: RetrievalService                                        │
│ - otpService: OtpService                                                    │
│ - notificationService: NotificationService                                  │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┐       ┌──────────────────────────────┐
│   LockerLocationService       │       │    ReservationService        │
│──────────────────────────────│       │──────────────────────────────│
│ - locations: Map<String,     │       │ - reservations: Map<String,  │
│     LockerLocation>          │       │     Reservation>             │
│──────────────────────────────│       │ - lockerAllocator:           │
│ + getLocation(id): Location  │       │     LockerAllocationStrategy │
│ + findNearby(lat, lng,       │       │──────────────────────────────│
│     radius): List<Location>  │       │ + reserve(pkg, locationId,   │
│ + listLockers(locationId):   │       │     customerId): Reservation │
│     List<Locker>             │       │ + cancel(resId): void        │
└──────────────────────────────┘       │ + expire(resId): void        │
                                       │ + getReservation(id): Res    │
┌──────────────────────────────┐       └──────────────────────────────┘
│      DepositService           │
│──────────────────────────────│       ┌──────────────────────────────┐
│ - otpService: OtpService     │       │   RetrievalService           │
│ - notifSvc: Notification     │       │──────────────────────────────│
│──────────────────────────────│       │ - otpService: OtpService     │
│ + depositPackage(resId,      │       │──────────────────────────────│
│     agentId): Confirmation   │       │ + retrievePackage(lockerId,  │
│ + rejectDeposit(resId,       │       │     otp): Package            │
│     reason): void            │       │ + reportWrongOtp(lockerId):  │
└──────────────────────────────┘       │     void                     │
                                       └──────────────────────────────┘

┌──────────────────────────────┐       ┌──────────────────────────────┐
│       OtpService              │       │   NotificationService        │
│──────────────────────────────│       │──────────────────────────────│
│ - activeOtps: Map<String,    │       │──────────────────────────────│
│     OtpEntry>                │       │ + sendDepositNotification(   │
│ - otpAttempts: Map<String,   │       │     customerId, lockerInfo,  │
│     Integer>                 │       │     otp): void               │
│──────────────────────────────│       │ + sendExpiryWarning(         │
│ + generateOtp(resId,         │       │     customerId): void        │
│     validHours): String      │       │ + sendPickupReceipt(         │
│ + verifyOtp(lockerId,        │       │     customerId): void        │
│     inputOtp): boolean       │       └──────────────────────────────┘
│ + invalidate(resId): void    │
│ + getRemainingAttempts(      │
│     lockerId): int           │
└──────────────────────────────┘
```

### Entity Classes

```
┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│   LockerLocation      │    │       Locker          │    │      Package         │
│──────────────────────│    │──────────────────────│    │──────────────────────│
│ - locationId: String  │    │ - lockerId: String    │    │ - packageId: String  │
│ - name: String        │    │ - locationId: String  │    │ - orderId: String    │
│ - address: Address    │    │ - size: LockerSize    │    │ - customerId: String │
│ - coordinates: GeoPt  │    │ - status: LockerStat  │    │ - size: PackageSize  │
│ - operatingHours      │    │ - currentReservation  │    │ - weightKg: double   │
│ - lockers: List<      │    │   Id: String          │    │ - dimensions: Dims   │
│     Locker>           │    │ - unlockAttempts: int │    │ - description:String │
└──────────────────────┘    │ - needsMaintenance:   │    └──────────────────────┘
                            │     boolean           │
┌──────────────────────┐    └──────────────────────┘    ┌──────────────────────┐
│     Reservation       │                               │     OtpEntry          │
│──────────────────────│    ┌──────────────────────┐    │──────────────────────│
│ - reservationId       │    │   DeliveryAgent       │    │ - otpHash: String    │
│ - orderId: String     │    │──────────────────────│    │   (bcrypt)           │
│ - customerId: String  │    │ - agentId: String     │    │ - reservationId      │
│ - lockerId: String    │    │ - name: String        │    │ - lockerId: String   │
│ - packageId: String   │    │ - vendorId: String    │    │ - customerId: String │
│ - status: ResStatus   │    │ - activeShift: bool   │    │ - generatedAt        │
│ - createdAt: Instant  │    └──────────────────────┘    │ - expiresAt: Instant │
│ - depositedAt: Instant│                               │ - used: boolean      │
│ - retrievedAt: Instant│                               └──────────────────────┘
│ - expiresAt: Instant  │
│ - otpEntry: OtpEntry  │
└──────────────────────┘
```

---

## 4. Enums & State Machines

```java
public enum LockerSize     { SMALL, MEDIUM, LARGE, XL }
public enum PackageSize    { SMALL, MEDIUM, LARGE, XL }  // matches LockerSize
public enum LockerStatus   { AVAILABLE, RESERVED, OCCUPIED, MAINTENANCE, OFFLINE }
public enum ReservationStatus { CREATED, DEPOSITED, RETRIEVED, EXPIRED, CANCELLED }
```

### Locker Status State Machine

```
                      ┌──────────────┐
       ┌─────────────▶│  AVAILABLE   │◀──────────────┐
       │              └──────┬───────┘               │ (customer retrieves,
       │       (system        │                      │  package out)
       │        reserves)     │ reserve()            │
       │                      ▼                      │
       │              ┌──────────────┐               │
       │              │   RESERVED   │               │
       │              └──────┬───────┘               │
       │                     │ depositPackage()      │
       │                     ▼                      │
       │              ┌──────────────┐               │
       │              │   OCCUPIED   │───────────────┘
       │              └──────┬───────┘
       │                     │
       │                     │ (tech reports issue)
       │                     ▼
       │              ┌──────────────┐
       └──────────────│ MAINTENANCE  │───(fixed)────▶ AVAILABLE
                      └──────────────┘
                             │
                             │ (hardware failure)
                             ▼
                      ┌──────────────┐
                      │   OFFLINE    │
                      └──────────────┘
```

### Reservation State Machine

```
┌─────────┐  depositPackage()  ┌───────────┐  retrievePackage()  ┌───────────┐
│ CREATED │───────────────────▶│ DEPOSITED │────────────────────▶│ RETRIEVED │
└────┬────┘                    └─────┬─────┘                     └───────────┘
     │                               │
     │ cancel (order canceled)       │ expire (3 days no pickup)
     ▼                               ▼
┌───────────┐                  ┌───────────┐
│ CANCELLED │                  │  EXPIRED  │
└───────────┘                  └───────────┘
```

---

## 5. Locker Allocation Strategy (Strategy Pattern)

```
┌──────────────────────────────────────┐
│  «interface» LockerAllocationStrategy │
│──────────────────────────────────────│
│ + findLocker(locationId, size):      │
│     Optional<Locker>                 │
└──────────────────┬───────────────────┘
                   │
      ┌────────────┼────────────────┐
      ▼            ▼                ▼
┌───────────┐ ┌────────────┐ ┌──────────────┐
│ Smallest  │ │ First      │ │ Balanced     │
│ Fit       │ │ Available  │ │ (distribute  │
│           │ │            │ │  across rows)│
│ Pick the  │ │ Pick first │ │              │
│ smallest  │ │ matching   │ │ Spread usage │
│ size that │ │ locker     │ │ evenly for   │
│ fits pkg  │ │            │ │ wear balance │
└───────────┘ └────────────┘ └──────────────┘
```

### Smallest Fit (Default)

```java
public class SmallestFitStrategy implements LockerAllocationStrategy {

    @Override
    public Optional<Locker> findLocker(LockerLocation location, PackageSize packageSize) {
        // Try exact size first, then upsize if none available
        LockerSize[] sizePreferenceOrder = getSizePreferenceOrder(packageSize);

        for (LockerSize size : sizePreferenceOrder) {
            Optional<Locker> locker = location.getLockers().stream()
                .filter(l -> l.getSize() == size)
                .filter(l -> l.getStatus() == LockerStatus.AVAILABLE)
                .findFirst();

            if (locker.isPresent()) return locker;
        }
        return Optional.empty();
    }

    private LockerSize[] getSizePreferenceOrder(PackageSize pkgSize) {
        // For SMALL package: try SMALL → MEDIUM → LARGE → XL
        // For XL package: only XL works
        switch (pkgSize) {
            case SMALL:  return new LockerSize[]{LockerSize.SMALL, LockerSize.MEDIUM,
                                                  LockerSize.LARGE, LockerSize.XL};
            case MEDIUM: return new LockerSize[]{LockerSize.MEDIUM, LockerSize.LARGE,
                                                  LockerSize.XL};
            case LARGE:  return new LockerSize[]{LockerSize.LARGE, LockerSize.XL};
            case XL:     return new LockerSize[]{LockerSize.XL};
            default:     throw new IllegalArgumentException("Unknown size");
        }
    }
}
```

---

## 6. Core Java Implementation

### 6.1 Locker

```java
public class Locker {
    private final String lockerId;
    private final String locationId;
    private final LockerSize size;
    private final int row;
    private final int column;

    private LockerStatus status;
    private String currentReservationId;
    private int failedUnlockAttempts;

    public Locker(String lockerId, String locationId, LockerSize size,
                  int row, int column) {
        this.lockerId = lockerId;
        this.locationId = locationId;
        this.size = size;
        this.row = row;
        this.column = column;
        this.status = LockerStatus.AVAILABLE;
        this.failedUnlockAttempts = 0;
    }

    public synchronized void reserve(String reservationId) {
        if (status != LockerStatus.AVAILABLE) {
            throw new LockerNotAvailableException(lockerId);
        }
        this.status = LockerStatus.RESERVED;
        this.currentReservationId = reservationId;
    }

    public synchronized void markDeposited() {
        if (status != LockerStatus.RESERVED) {
            throw new InvalidLockerStateException(
                "Cannot deposit — locker not in RESERVED state");
        }
        this.status = LockerStatus.OCCUPIED;
    }

    public synchronized void markRetrieved() {
        if (status != LockerStatus.OCCUPIED) {
            throw new InvalidLockerStateException(
                "Cannot retrieve — locker not in OCCUPIED state");
        }
        this.status = LockerStatus.AVAILABLE;
        this.currentReservationId = null;
        this.failedUnlockAttempts = 0;
    }

    public synchronized void incrementFailedAttempts() {
        failedUnlockAttempts++;
        if (failedUnlockAttempts >= 3) {
            this.status = LockerStatus.MAINTENANCE;  // lock out, require human intervention
        }
    }

    public synchronized void markMaintenance() { this.status = LockerStatus.MAINTENANCE; }
    public synchronized void markAvailable()   { this.status = LockerStatus.AVAILABLE; }

    public boolean isAvailable() { return status == LockerStatus.AVAILABLE; }

    public boolean canFit(PackageSize packageSize) {
        // Locker must be at least as big as the package
        return size.ordinal() >= packageSize.ordinal();
    }

    // getters...
}
```

### 6.2 LockerLocation

```java
public class LockerLocation {
    private final String locationId;
    private final String name;
    private final Address address;
    private final GeoPoint coordinates;
    private final OperatingHours operatingHours;
    private final List<Locker> lockers;
    private final Map<LockerSize, Integer> totalCountBySize;

    public LockerLocation(String locationId, String name, Address address,
                           GeoPoint coordinates, List<Locker> lockers) {
        this.locationId = locationId;
        this.name = name;
        this.address = address;
        this.coordinates = coordinates;
        this.operatingHours = OperatingHours.defaultHours();
        this.lockers = lockers;
        this.totalCountBySize = computeCounts(lockers);
    }

    public int getAvailableCount(LockerSize size) {
        return (int) lockers.stream()
            .filter(l -> l.getSize() == size && l.isAvailable())
            .count();
    }

    public List<Locker> getAvailableLockers(LockerSize size) {
        return lockers.stream()
            .filter(l -> l.getSize() == size && l.isAvailable())
            .collect(Collectors.toList());
    }

    public boolean hasCapacity(PackageSize packageSize) {
        return lockers.stream()
            .anyMatch(l -> l.canFit(packageSize) && l.isAvailable());
    }

    public double distanceTo(GeoPoint other) {
        return coordinates.haversineDistance(other);  // km
    }

    // getters...
}
```

### 6.3 OtpService (Security-Critical)

```java
public class OtpService {
    private static final int OTP_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 3;
    private static final int DEFAULT_VALID_HOURS = 72;  // 3 days

    private final Map<String, OtpEntry> otpByReservation = new ConcurrentHashMap<>();
    private final Map<String, OtpEntry> otpByLocker = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attemptsByLocker = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final PasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public String generateOtp(String reservationId, String lockerId, String customerId) {
        // Generate cryptographically secure 6-digit OTP
        String plainOtp = generateSecureOtp();
        String otpHash = bcrypt.encode(plainOtp);

        Instant now = Instant.now();
        OtpEntry entry = new OtpEntry(
            otpHash, reservationId, lockerId, customerId,
            now, now.plus(DEFAULT_VALID_HOURS, ChronoUnit.HOURS),
            false
        );

        otpByReservation.put(reservationId, entry);
        otpByLocker.put(lockerId, entry);
        attemptsByLocker.put(lockerId, new AtomicInteger(0));

        return plainOtp;  // return PLAIN for notification — never stored
    }

    public boolean verifyOtp(String lockerId, String inputOtp) {
        OtpEntry entry = otpByLocker.get(lockerId);
        if (entry == null) throw new OtpNotFoundException(lockerId);

        if (entry.isUsed()) throw new OtpAlreadyUsedException();
        if (Instant.now().isAfter(entry.getExpiresAt())) {
            throw new OtpExpiredException();
        }

        // Check attempt limit
        AtomicInteger attempts = attemptsByLocker.get(lockerId);
        if (attempts.get() >= MAX_ATTEMPTS) {
            throw new OtpAttemptsExceededException(lockerId);
        }

        // Constant-time compare via bcrypt
        boolean matches = bcrypt.matches(inputOtp, entry.getOtpHash());

        if (!matches) {
            attempts.incrementAndGet();
            return false;
        }

        // Success — mark OTP as used, prevent replay
        entry.setUsed(true);
        return true;
    }

    public int getRemainingAttempts(String lockerId) {
        AtomicInteger attempts = attemptsByLocker.get(lockerId);
        if (attempts == null) return MAX_ATTEMPTS;
        return Math.max(0, MAX_ATTEMPTS - attempts.get());
    }

    public void invalidate(String reservationId) {
        OtpEntry entry = otpByReservation.remove(reservationId);
        if (entry != null) {
            otpByLocker.remove(entry.getLockerId());
            attemptsByLocker.remove(entry.getLockerId());
        }
    }

    private String generateSecureOtp() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }
}
```

### 6.4 ReservationService

```java
public class ReservationService {
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final LockerLocationService locationService;
    private final LockerAllocationStrategy allocator;

    public ReservationService(LockerLocationService locationService,
                              LockerAllocationStrategy allocator) {
        this.locationService = locationService;
        this.allocator = allocator;
    }

    public Reservation reserve(String orderId, String customerId,
                               String locationId, Package pkg) {
        LockerLocation location = locationService.getLocation(locationId);
        if (location == null) throw new LocationNotFoundException(locationId);

        // Find an appropriate locker
        Optional<Locker> locker = allocator.findLocker(location, pkg.getSize());
        if (locker.isEmpty()) {
            throw new NoLockersAvailableException(
                "No " + pkg.getSize() + " lockers available at " + locationId);
        }

        // Reserve atomically
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        locker.get().reserve(reservationId);   // synchronized in Locker

        Reservation reservation = new Reservation(
            reservationId, orderId, customerId, locker.get().getLockerId(),
            pkg.getPackageId(), ReservationStatus.CREATED, Instant.now()
        );
        reservations.put(reservationId, reservation);
        return reservation;
    }

    public void cancel(String reservationId) {
        Reservation res = reservations.get(reservationId);
        if (res == null) throw new ReservationNotFoundException(reservationId);
        if (res.getStatus() != ReservationStatus.CREATED) {
            throw new IllegalStateException("Cannot cancel — already deposited");
        }

        res.setStatus(ReservationStatus.CANCELLED);
        // Release the locker
        Locker locker = locationService.getLocker(res.getLockerId());
        locker.markAvailable();
    }

    public void expire(String reservationId) {
        Reservation res = reservations.get(reservationId);
        res.setStatus(ReservationStatus.EXPIRED);
        // Package is still in locker — ops team retrieves it manually
        // Locker stays OCCUPIED until ops intervention
    }

    public Reservation getReservation(String id) {
        return reservations.get(id);
    }
}
```

### 6.5 DepositService

```java
public class DepositService {
    private final ReservationService reservationService;
    private final LockerLocationService locationService;
    private final OtpService otpService;
    private final NotificationService notificationService;

    public DepositConfirmation depositPackage(String reservationId, String agentId) {
        Reservation reservation = reservationService.getReservation(reservationId);
        if (reservation == null) throw new ReservationNotFoundException(reservationId);
        if (reservation.getStatus() != ReservationStatus.CREATED) {
            throw new InvalidReservationStateException(
                "Reservation not ready for deposit: " + reservation.getStatus());
        }

        Locker locker = locationService.getLocker(reservation.getLockerId());

        // 1. Unlock locker (physical hardware call — abstracted)
        LockerHardware.unlock(locker.getLockerId());

        // 2. Wait for agent to deposit and close door (simulated by API call)
        // In reality: locker sensor detects door close → callback

        // 3. Mark locker OCCUPIED
        locker.markDeposited();

        // 4. Update reservation
        reservation.setStatus(ReservationStatus.DEPOSITED);
        reservation.setDepositedAt(Instant.now());

        // 5. Generate OTP
        String plainOtp = otpService.generateOtp(
            reservationId, locker.getLockerId(), reservation.getCustomerId());

        // 6. Notify customer (push + SMS + email)
        notificationService.sendDepositNotification(
            reservation.getCustomerId(),
            locker.getLocationId(),
            locker.getLockerId(),
            plainOtp
        );

        return new DepositConfirmation(reservationId, locker.getLockerId(),
                                        Instant.now());
    }
}
```

### 6.6 RetrievalService

```java
public class RetrievalService {
    private final OtpService otpService;
    private final LockerLocationService locationService;
    private final ReservationService reservationService;
    private final NotificationService notificationService;

    public RetrievalResult retrievePackage(String lockerId, String inputOtp) {
        Locker locker = locationService.getLocker(lockerId);
        if (locker == null) throw new LockerNotFoundException(lockerId);

        if (locker.getStatus() != LockerStatus.OCCUPIED) {
            throw new LockerNotOccupiedException(lockerId);
        }

        // Verify OTP
        boolean valid;
        try {
            valid = otpService.verifyOtp(lockerId, inputOtp);
        } catch (OtpAttemptsExceededException e) {
            locker.markMaintenance();  // lock out, escalate
            notificationService.sendSecurityAlert(locker.getCurrentReservationId());
            throw e;
        }

        if (!valid) {
            locker.incrementFailedAttempts();
            int remaining = otpService.getRemainingAttempts(lockerId);
            return RetrievalResult.failure(
                "Invalid OTP. " + remaining + " attempts remaining");
        }

        // OTP verified — unlock the locker
        LockerHardware.unlock(lockerId);

        // Mark retrieved
        Reservation reservation = reservationService.getReservation(
            locker.getCurrentReservationId());
        reservation.setStatus(ReservationStatus.RETRIEVED);
        reservation.setRetrievedAt(Instant.now());

        locker.markRetrieved();  // status back to AVAILABLE

        // Invalidate OTP (single-use)
        otpService.invalidate(reservation.getReservationId());

        // Send receipt
        notificationService.sendPickupReceipt(reservation.getCustomerId());

        return RetrievalResult.success(reservation.getPackageId());
    }
}
```

---

## 7. Flow Diagrams

### Flow 1: Order & Reservation (at Checkout)

```
Customer checks out with Locker delivery
     │
     ▼
┌──────────────────────────┐
│ Amazon Order Service     │
│ calls:                   │
│ reserveLocker(packageDims│
│   locationId, customerId)│
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ ReservationService       │
│                          │
│ 1. Validate location     │
│    exists                │
│                          │
│ 2. Determine package size│
│    from dimensions       │
│                          │
│ 3. Call allocator:       │
│    findLocker(location,  │
│      packageSize)        │
│                          │
│    SmallestFit strategy: │
│    - Try exact size      │
│    - Upsize if full      │
│                          │
│ 4. Reserve locker        │
│    atomically            │
│    (locker.reserve() is  │
│     synchronized)        │
│                          │
│ 5. Create Reservation    │
│    status = CREATED      │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Return reservationId to  │
│ Order Service            │
│                          │
│ Order stored with:       │
│   deliveryType = LOCKER  │
│   reservationId          │
│   lockerId               │
└──────────────────────────┘
```

### Flow 2: Package Deposit (Delivery Agent)

```
Delivery agent arrives at locker kiosk
     │
     ▼
┌──────────────────────────┐
│ Agent scans package      │
│ barcode                  │
│                          │
│ Kiosk looks up:          │
│   packageId → reservation│
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ DepositService           │
│ .depositPackage(resId,   │
│   agentId)               │
│                          │
│ 1. Validate reservation  │
│    status == CREATED     │
│                          │
│ 2. Send unlock command   │
│    to locker hardware    │
│    (locker door opens)   │
│                          │
│ 3. Agent places package  │
│    and closes door       │
│                          │
│ 4. Hardware sensor        │
│    confirms door closed  │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ 5. locker.markDeposited()│
│    → status = OCCUPIED   │
│                          │
│ 6. reservation.status =  │
│    DEPOSITED             │
│                          │
│ 7. OtpService.generateOtp│
│    → 6-digit secure OTP  │
│    → hash stored, plain  │
│      returned ONCE       │
│    → valid 72 hours      │
│                          │
│ 8. NotificationService   │
│    sends to customer:    │
│    - SMS with OTP        │
│    - Email with OTP +    │
│      address             │
│    - Push notification   │
└──────────────────────────┘
```

### Flow 3: Package Retrieval (Customer Pickup)

```
Customer arrives at locker location
     │
     ▼
┌──────────────────────────┐
│ Customer enters OTP at   │
│ kiosk touchscreen        │
│                          │
│ POST /retrieve            │
│   { lockerId, otp }      │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ RetrievalService         │
│                          │
│ 1. Look up locker        │
│    Verify status ==      │
│    OCCUPIED              │
│                          │
│ 2. OtpService.verifyOtp()│
│                          │
│    Check attempt limit:  │
│    If >= 3 attempts:     │
│      → lock out          │
│      → locker MAINTENANCE│
│      → alert security    │
│                          │
│    Check expiry:         │
│      If > 72h old:       │
│      → OtpExpired        │
│                          │
│    Verify hash:          │
│      bcrypt.matches(     │
│        input, stored)    │
└────────┬─────────────────┘
         │
         ├─── Invalid OTP ──────────────┐
         │                              ▼
         │                   ┌──────────────────┐
         │                   │ Increment attempt│
         │                   │ counter          │
         │                   │                  │
         │                   │ Display:         │
         │                   │ "Invalid OTP.    │
         │                   │  N attempts left"│
         │                   └──────────────────┘
         │
         ├─── Valid OTP ────────────────┐
         │                              ▼
         │                   ┌──────────────────┐
         │                   │ Hardware unlock  │
         │                   │ (locker opens)   │
         │                   │                  │
         │                   │ Customer takes   │
         │                   │ package, closes  │
         │                   │ door             │
         │                   │                  │
         │                   │ locker.mark-     │
         │                   │ Retrieved()      │
         │                   │ → AVAILABLE      │
         │                   │                  │
         │                   │ reservation →    │
         │                   │ RETRIEVED        │
         │                   │                  │
         │                   │ OTP invalidated  │
         │                   │ (single-use)     │
         │                   │                  │
         │                   │ Send pickup      │
         │                   │ receipt email    │
         │                   └──────────────────┘
```

### Flow 4: Expiry Handling (3 days uncollected)

```
Async scheduler runs every hour:
     │
     ▼
┌──────────────────────────┐
│ ExpiryWorker             │
│                          │
│ Query reservations where:│
│   status = DEPOSITED     │
│   expiresAt < now        │
│                          │
│ For each expired:        │
│  1. Mark reservation     │
│     EXPIRED              │
│  2. Invalidate OTP       │
│  3. Notify customer:     │
│     "Package not picked  │
│      up — will be        │
│      returned to sender" │
│  4. Notify ops team:     │
│     "Locker L-042 has    │
│      expired package —   │
│      manual retrieval"   │
│  5. Locker stays OCCUPIED│
│     until ops clears it  │
│                          │
│ Warning at 48h:          │
│  Send reminder email     │
│  "24 hours left to pick  │
│   up your package"       │
└──────────────────────────┘
```

### Flow 5: Customer Finds Nearby Locker

```
Customer opens Amazon app →
"Ship to locker"
     │
     ▼
┌──────────────────────────┐
│ App sends:               │
│ GET /locations/nearby    │
│   ?lat=47.6&lng=-122.3   │
│   &radius=5km            │
│   &packageSize=MEDIUM    │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ LockerLocationService    │
│                          │
│ 1. Geo query:            │
│    Query locations within│
│    radius km of (lat,lng)│
│    (uses R-tree / geo    │
│     index on lat,lng)    │
│                          │
│ 2. Filter:               │
│    hasCapacity(MEDIUM)   │
│    → location has at     │
│    least 1 MEDIUM+ locker│
│    available             │
│                          │
│ 3. Sort by distance ASC  │
│                          │
│ 4. Return top 10:        │
│    [{locationId, name,   │
│      address, distance,  │
│      availableSlots}]    │
└──────────────────────────┘
```

---

## 8. DynamoDB Schema (for production scale)

```
Table: LockerLocations
  PK: locationId
  Attrs: name, address, lat, lng, operatingHours
  GSI: GeoIndex — for "find nearby" queries (often uses external geo-index
                   like ElasticSearch or S2 cells as PK)

Table: Lockers
  PK: lockerId
  Attrs: locationId, size, status, currentReservationId,
         failedUnlockAttempts
  GSI: LocationStatusIndex
    PK: locationId#size
    SK: status
    → "All AVAILABLE MEDIUM lockers at location X"

Table: Reservations
  PK: reservationId
  Attrs: orderId, customerId, lockerId, packageId, status,
         createdAt, depositedAt, retrievedAt, expiresAt
  GSI: CustomerReservationsIndex
    PK: customerId
    SK: createdAt
    → "My active pickups"
  GSI: ExpiryIndex
    PK: status (filtered to DEPOSITED)
    SK: expiresAt
    → "Reservations expiring soon" (for ExpiryWorker)

Table: Otps
  PK: lockerId
  Attrs: otpHash, reservationId, customerId, generatedAt,
         expiresAt, used
  TTL: expiresAt Unix epoch — auto-delete expired OTPs
  Note: OTP hash only — never store plaintext
```

---

## 9. Security Considerations

```
┌──────────────────────────────────────────────────────────────────┐
│  1. OTP Generation                                                │
│     - SecureRandom (CSPRNG), not regular Random                  │
│     - 6 digits = 10^6 = 1M combinations                          │
│     - Rate limiting: 3 attempts per locker per OTP               │
│     - With 3 attempts: 3/10^6 chance of guessing                 │
│                                                                  │
│  2. OTP Storage                                                  │
│     - bcrypt hash with cost factor 10+ (never plaintext)         │
│     - Stored with 72h TTL (auto-cleanup)                         │
│     - Single-use: invalidated after successful retrieval         │
│                                                                  │
│  3. OTP Transmission                                             │
│     - SMS over SS7 (weak) → prefer Amazon app push               │
│     - Email (weak without MFA) → push + SMS as fallback          │
│     - Never in URL query params (logged)                         │
│                                                                  │
│  4. Brute Force Protection                                       │
│     - 3 failed attempts → locker MAINTENANCE                     │
│     - Customer must call support (identity verification)         │
│     - Security alert logged + ops team notified                  │
│                                                                  │
│  5. Replay Attack                                                │
│     - OTP marked used=true after first success                   │
│     - Second use rejected immediately                            │
│                                                                  │
│  6. Physical Security                                            │
│     - Locker door sensor — detect forced entry                  │
│     - Camera at each locker cabinet                              │
│     - Tamper log sent to security team                           │
└──────────────────────────────────────────────────────────────────┘
```

---

## 10. End-to-End Scenario

```
Day 0 — 10:00 AM
  Customer "cust-42" orders on Amazon.
  Chooses "Ship to Locker" → selects "Seattle Downtown Locker"
  Package is MEDIUM size.

  Order Service → ReservationService.reserve(
    orderId="ord-999", customerId="cust-42",
    locationId="loc-sea-downtown",
    package={size: MEDIUM, dims: 30x30x20})
    
  Strategy: SmallestFit finds L-042 (MEDIUM, AVAILABLE)
  → L-042 reserved, status = RESERVED
  → reservationId = "RES-abc12345"
  → Customer confirmation: "We'll notify you when it's ready"

Day 2 — 2:30 PM
  Delivery agent arrives at Seattle Downtown.
  Scans package barcode → kiosk looks up reservation
  
  DepositService.depositPackage("RES-abc12345", "agent-007")
  → L-042 unlocks
  → Agent deposits, closes door
  → L-042 marked OCCUPIED
  → reservation.status = DEPOSITED
  → OTP "384729" generated (bcrypt stored, plain returned once)
  → Customer notified via:
    Push: "Your package is ready! OTP: 384729"
    Email: "Pickup at 101 Main St, Seattle. OTP: 384729. Valid 72h."
    SMS: "Amazon Locker: pickup code 384729"

Day 3 — 6:00 PM
  Customer walks to kiosk.
  Enters "384729" at L-042 touchscreen.
  
  RetrievalService.retrievePackage("L-042", "384729")
  → OTP verified (bcrypt.matches → true)
  → L-042 unlocks
  → Customer takes package, closes door
  → L-042 marked AVAILABLE
  → reservation.status = RETRIEVED
  → OTP invalidated
  → Pickup receipt emailed
  
Day 3 — 6:05 PM
  L-042 is ready for next reservation.

Alt: Day 5 (no pickup)
  ExpiryWorker runs at 10:00 AM.
  Finds reservation expired.
  → Customer notified: "Package returned to sender"
  → Ops ticket created: "Retrieve expired package from L-042"
  → L-042 stays OCCUPIED until ops clears it
```
