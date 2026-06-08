package parkinglot;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Parking Lot System — Enhanced Interview Version
 *
 * Core (from simple version):
 *   - enter(vehicleType) → Ticket
 *   - exit(ticketId) → fee
 *
 * Enhanced with LLD patterns:
 *   - Multi-level building with per-type spot managers
 *   - Strategy: Spot lookup (FirstAvailable, NearestEntrance)
 *   - Strategy: Pricing (Fixed, Hourly, VehicleType-based)
 *   - Strategy: Payment (UPI, Cash, Card)
 *   - Concurrency: ReentrantLock per SpotManager
 *   - Observer: Capacity alerts
 *   - Gates: Entrance/Exit separation
 */
public class ParkingLotv2 {

    // ═══════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════

    enum VehicleType { MOTORCYCLE, CAR, LARGE }
    enum SpotType { MOTORCYCLE, CAR, LARGE }

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class ParkingSpot {
        private final String id;
        private final SpotType spotType;
        private boolean free = true;

        public ParkingSpot(String id, SpotType spotType) {
            this.id = id;
            this.spotType = spotType;
        }

        public String getId() { return id; }
        public SpotType getSpotType() { return spotType; }
        public boolean isFree() { return free; }
        public void occupy() { free = false; }
        public void release() { free = true; }
    }

    static class Vehicle {
        private final String plateNumber;
        private final VehicleType type;

        public Vehicle(String plateNumber, VehicleType type) {
            this.plateNumber = plateNumber;
            this.type = type;
        }

        public String getPlateNumber() { return plateNumber; }
        public VehicleType getType() { return type; }
    }

    static class Ticket {
        private final String ticketId;
        private final String spotId;
        private final VehicleType vehicleType;
        private final long entryTime;
        private final int level;

        public Ticket(String ticketId, String spotId, VehicleType vehicleType, long entryTime, int level) {
            this.ticketId = ticketId;
            this.spotId = spotId;
            this.vehicleType = vehicleType;
            this.entryTime = entryTime;
            this.level = level;
        }

        public String getTicketId() { return ticketId; }
        public String getSpotId() { return spotId; }
        public VehicleType getVehicleType() { return vehicleType; }
        public long getEntryTime() { return entryTime; }
        public int getLevel() { return level; }
    }

    // ═══════════════════════════════════════════════
    // Strategy: Spot Lookup
    // ═══════════════════════════════════════════════

    interface SpotLookupStrategy {
        ParkingSpot findSpot(List<ParkingSpot> spots);
    }

    static class FirstAvailableStrategy implements SpotLookupStrategy {
        @Override
        public ParkingSpot findSpot(List<ParkingSpot> spots) {
            for (ParkingSpot spot : spots) {
                if (spot.isFree()) return spot;
            }
            return null;
        }
    }

    // ═══════════════════════════════════════════════
    // Strategy: Pricing
    // ═══════════════════════════════════════════════

    interface PricingStrategy {
        long computeFee(Ticket ticket, long exitTime);
    }

    static class HourlyPricingStrategy implements PricingStrategy {
        private final long hourlyRateCents;

        public HourlyPricingStrategy(long hourlyRateCents) {
            this.hourlyRateCents = hourlyRateCents;
        }

        @Override
        public long computeFee(Ticket ticket, long exitTime) {
            long durationMillis = exitTime - ticket.getEntryTime();
            long durationHours = durationMillis / (1000 * 60 * 60);
            if (durationMillis % (1000 * 60 * 60) > 0) durationHours++; // round up
            return Math.max(1, durationHours) * hourlyRateCents; // minimum 1 hour
        }
    }

    static class VehicleTypePricingStrategy implements PricingStrategy {
        private final Map<VehicleType, Long> ratesPerHour;

        public VehicleTypePricingStrategy(Map<VehicleType, Long> ratesPerHour) {
            this.ratesPerHour = ratesPerHour;
        }

        @Override
        public long computeFee(Ticket ticket, long exitTime) {
            long rate = ratesPerHour.getOrDefault(ticket.getVehicleType(), 100L);
            long durationMillis = exitTime - ticket.getEntryTime();
            long durationHours = durationMillis / (1000 * 60 * 60);
            if (durationMillis % (1000 * 60 * 60) > 0) durationHours++;
            return Math.max(1, durationHours) * rate;
        }
    }

    // ═══════════════════════════════════════════════
    // Strategy: Payment
    // ═══════════════════════════════════════════════

    interface Payment {
        boolean pay(long amountCents);
    }

    static class CashPayment implements Payment {
        @Override
        public boolean pay(long amountCents) {
            System.out.println("  Cash paid: $" + (amountCents / 100.0));
            return true;
        }
    }

    static class UPIPayment implements Payment {
        @Override
        public boolean pay(long amountCents) {
            System.out.println("  UPI paid: $" + (amountCents / 100.0));
            return true;
        }
    }

    // ═══════════════════════════════════════════════
    // Observer: Capacity Alert
    // ═══════════════════════════════════════════════

    interface CapacityListener {
        void onCapacityChange(SpotType type, int totalSpots, int freeSpots);
    }

    // ═══════════════════════════════════════════════
    // Spot Manager (thread-safe per vehicle type)
    // ═══════════════════════════════════════════════

    static class SpotManager {
        private final List<ParkingSpot> spots;
        private final SpotLookupStrategy strategy;
        private final java.util.concurrent.locks.ReadWriteLock rwLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

        public SpotManager(List<ParkingSpot> spots, SpotLookupStrategy strategy) {
            this.spots = spots;
            this.strategy = strategy;
        }

        /**
         * Park a vehicle using optimistic read → write escalation.
         *
         * Why ReadWriteLock:
         *   - findSpot() only READS occupancy state → multiple threads can search concurrently
         *   - occupy() WRITES → needs exclusive access
         *   - In a busy lot, most operations are reads (checking availability), few are writes
         *
         * Pattern: Read to find candidate, then Write to claim it.
         * Must re-verify inside write lock (spot may have been taken between read and write).
         */
        public ParkingSpot park() {
            while (true) {
                // Phase 1: Read lock — find a candidate spot (concurrent with other readers)
                ParkingSpot candidate;
                rwLock.readLock().lock();
                try {
                    candidate = strategy.findSpot(spots);
                } finally {
                    rwLock.readLock().unlock();
                }

                if (candidate == null) return null; // lot is full

                // Phase 2: Write lock — claim the spot exclusively
                rwLock.writeLock().lock();
                try {
                    // Re-verify: another thread may have taken this spot
                    // between our read and write lock acquisition
                    if (candidate.isFree()) {
                        candidate.occupy();
                        return candidate;
                    }
                    // Spot was taken — loop back and try again
                } finally {
                    rwLock.writeLock().unlock();
                }
            }
        }

        public void unPark(ParkingSpot spot) {
            rwLock.writeLock().lock();
            try {
                spot.release();
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public boolean hasFreeSpot() {
            rwLock.readLock().lock();
            try {
                return spots.stream().anyMatch(ParkingSpot::isFree);
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public int getFreeCount() {
            rwLock.readLock().lock();
            try {
                return (int) spots.stream().filter(ParkingSpot::isFree).count();
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public int getTotalCount() { return spots.size(); }
    }

    // ═══════════════════════════════════════════════
    // Parking Level
    // ═══════════════════════════════════════════════

    static class ParkingLevel {
        private final int levelNum;
        private final Map<SpotType, SpotManager> managers;

        public ParkingLevel(int levelNum, Map<SpotType, SpotManager> managers) {
            this.levelNum = levelNum;
            this.managers = managers;
        }

        public int getLevelNum() { return levelNum; }

        public boolean hasAvailability(SpotType type) {
            SpotManager mgr = managers.get(type);
            return mgr != null && mgr.hasFreeSpot();
        }

        public ParkingSpot park(SpotType type) {
            SpotManager mgr = managers.get(type);
            if (mgr == null) return null;
            return mgr.park();
        }

        public void unPark(SpotType type, ParkingSpot spot) {
            SpotManager mgr = managers.get(type);
            if (mgr != null) mgr.unPark(spot);
        }

        public int getFreeCount(SpotType type) {
            SpotManager mgr = managers.get(type);
            return mgr != null ? mgr.getFreeCount() : 0;
        }
    }

    // ═══════════════════════════════════════════════
    // Parking Lot (Full System)
    // ═══════════════════════════════════════════════

    static class ParkingLot {
        private final List<ParkingLevel> levels;
        private final Map<String, Ticket> activeTickets;
        private final Map<String, ParkingSpot> ticketToSpot; // for releasing on exit
        private final PricingStrategy pricingStrategy;
        private final List<CapacityListener> listeners;

        public ParkingLot(List<ParkingLevel> levels, PricingStrategy pricingStrategy) {
            this.levels = levels;
            this.activeTickets = new HashMap<>();
            this.ticketToSpot = new HashMap<>();
            this.pricingStrategy = pricingStrategy;
            this.listeners = new ArrayList<>();
        }

        public void addCapacityListener(CapacityListener listener) {
            listeners.add(listener);
        }

        /**
         * Vehicle enters: find spot → issue ticket.
         */
        public Ticket enter(Vehicle vehicle) {
            SpotType requiredType = mapVehicleToSpot(vehicle.getType());

            for (ParkingLevel level : levels) {
                if (level.hasAvailability(requiredType)) {
                    ParkingSpot spot = level.park(requiredType);
                    if (spot != null) {
                        String ticketId = UUID.randomUUID().toString().substring(0, 8);
                        Ticket ticket = new Ticket(ticketId, spot.getId(), vehicle.getType(),
                                                    System.currentTimeMillis(), level.getLevelNum());
                        activeTickets.put(ticketId, ticket);
                        ticketToSpot.put(ticketId, spot);

                        System.out.println("  Parked: " + vehicle.getPlateNumber()
                            + " → Level " + level.getLevelNum() + ", Spot " + spot.getId());

                        notifyListeners(requiredType, level);
                        return ticket;
                    }
                }
            }
            throw new RuntimeException("No available spots for " + vehicle.getType());
        }

        /**
         * Vehicle exits: compute fee → pay → release spot.
         */
        public long exit(String ticketId, Payment payment) {
            Ticket ticket = activeTickets.get(ticketId);
            if (ticket == null) throw new RuntimeException("Ticket not found: " + ticketId);

            long exitTime = System.currentTimeMillis();
            long fee = pricingStrategy.computeFee(ticket, exitTime);

            boolean success = payment.pay(fee);
            if (!success) throw new RuntimeException("Payment failed. EXIT DENIED");

            // Release spot
            ParkingSpot spot = ticketToSpot.get(ticketId);
            SpotType type = mapVehicleToSpot(ticket.getVehicleType());
            ParkingLevel level = levels.get(findLevelIndex(ticket.getLevel()));
            level.unPark(type, spot);

            activeTickets.remove(ticketId);
            ticketToSpot.remove(ticketId);

            System.out.println("  Exit: Spot " + spot.getId() + " freed");
            notifyListeners(type, level);

            return fee;
        }

        /**
         * Check availability across all levels.
         */
        public int getAvailableSpots(VehicleType vehicleType) {
            SpotType type = mapVehicleToSpot(vehicleType);
            int total = 0;
            for (ParkingLevel level : levels) {
                total += level.getFreeCount(type);
            }
            return total;
        }

        private SpotType mapVehicleToSpot(VehicleType vehicleType) {
            switch (vehicleType) {
                case MOTORCYCLE: return SpotType.MOTORCYCLE;
                case CAR: return SpotType.CAR;
                case LARGE: return SpotType.LARGE;
                default: throw new RuntimeException("Unknown vehicle type");
            }
        }

        private int findLevelIndex(int levelNum) {
            for (int i = 0; i < levels.size(); i++) {
                if (levels.get(i).getLevelNum() == levelNum) return i;
            }
            return 0;
        }

        private void notifyListeners(SpotType type, ParkingLevel level) {
            for (CapacityListener listener : listeners) {
                SpotManager mgr = level.managers.get(type);
                if (mgr != null) {
                    listener.onCapacityChange(type, mgr.getTotalCount(), mgr.getFreeCount());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        // ─── Build parking structure ───
        SpotLookupStrategy lookupStrategy = new FirstAvailableStrategy();

        // Level 1: 3 motorcycle, 5 car, 2 large
        List<ParkingSpot> motoSpots = new ArrayList<>();
        for (int i = 1; i <= 3; i++) motoSpots.add(new ParkingSpot("L1-M" + i, SpotType.MOTORCYCLE));

        List<ParkingSpot> carSpots = new ArrayList<>();
        for (int i = 1; i <= 5; i++) carSpots.add(new ParkingSpot("L1-C" + i, SpotType.CAR));

        List<ParkingSpot> largeSpots = new ArrayList<>();
        for (int i = 1; i <= 2; i++) largeSpots.add(new ParkingSpot("L1-L" + i, SpotType.LARGE));

        Map<SpotType, SpotManager> level1Managers = new HashMap<>();
        level1Managers.put(SpotType.MOTORCYCLE, new SpotManager(motoSpots, lookupStrategy));
        level1Managers.put(SpotType.CAR, new SpotManager(carSpots, lookupStrategy));
        level1Managers.put(SpotType.LARGE, new SpotManager(largeSpots, lookupStrategy));

        ParkingLevel level1 = new ParkingLevel(1, level1Managers);
        List<ParkingLevel> levels = new ArrayList<>();
        levels.add(level1);

        // ─── Build system with pricing ───
        Map<VehicleType, Long> rates = new HashMap<>();
        rates.put(VehicleType.MOTORCYCLE, 50L);   // $0.50/hr
        rates.put(VehicleType.CAR, 200L);         // $2.00/hr
        rates.put(VehicleType.LARGE, 500L);       // $5.00/hr

        PricingStrategy pricing = new VehicleTypePricingStrategy(rates);
        ParkingLot lot = new ParkingLot(levels, pricing);

        // ─── Add capacity alert ───
        lot.addCapacityListener((type, total, free) -> {
            double pct = (double) free / total * 100;
            if (pct <= 20) {
                System.out.println("  ⚠ ALERT: " + type + " spots almost full! " + free + "/" + total + " free");
            }
        });

        // ─── Demo ───
        System.out.println("═══ Parking Lot System (Enhanced) ═══\n");
        System.out.println("Available: " + lot.getAvailableSpots(VehicleType.CAR) + " car spots");

        System.out.println("\n--- Vehicles Entering ---");
        Ticket t1 = lot.enter(new Vehicle("KA-01-1234", VehicleType.CAR));
        Ticket t2 = lot.enter(new Vehicle("KA-02-5678", VehicleType.CAR));
        Ticket t3 = lot.enter(new Vehicle("KA-03-BIKE", VehicleType.MOTORCYCLE));
        Ticket t4 = lot.enter(new Vehicle("KA-04-TRUCK", VehicleType.LARGE));
        Ticket t5 = lot.enter(new Vehicle("KA-05-9999", VehicleType.CAR));
        Ticket t6 = lot.enter(new Vehicle("KA-06-0000", VehicleType.CAR));

        System.out.println("\nAvailable car spots: " + lot.getAvailableSpots(VehicleType.CAR));

        // Exit
        System.out.println("\n--- Vehicles Exiting ---");
        lot.exit(t1.getTicketId(), new UPIPayment());
        lot.exit(t3.getTicketId(), new CashPayment());

        System.out.println("\nAvailable car spots after exit: " + lot.getAvailableSpots(VehicleType.CAR));

        // Fill up to trigger alert
        System.out.println("\n--- Fill remaining car spots ---");
        lot.enter(new Vehicle("KA-07-FILL1", VehicleType.CAR));
        lot.enter(new Vehicle("KA-08-FILL2", VehicleType.CAR));

        // Try one more (should fail)
        System.out.println("\n--- Attempt when full ---");
        try {
            lot.enter(new Vehicle("KA-11-NOPE", VehicleType.CAR));
        } catch (RuntimeException e) {
            System.out.println("  " + e.getMessage());
        }
    }
}
