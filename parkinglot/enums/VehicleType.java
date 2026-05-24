package parkinglot.enums;

import java.util.Set;

/**
 * Each vehicle type declares which spot types it can fit in,
 * and how many spots it requires.
 *
 * Adding a new vehicle type (e.g., ELECTRIC_SCOOTER) only requires
 * adding a new enum constant here — no changes to ParkingSpot or EntryGate.
 */
public enum VehicleType {
    MOTORCYCLE (1, Set.of(SpotType.SMALL, SpotType.MEDIUM, SpotType.LARGE)),
    CAR        (1, Set.of(SpotType.MEDIUM, SpotType.LARGE)),
    TRUCK      (1, Set.of(SpotType.LARGE)),
    BUS        (3, Set.of(SpotType.LARGE));  // 3 consecutive LARGE spots

    private final int requiredSpots;
    private final Set<SpotType> compatibleSpotTypes;

    VehicleType(int requiredSpots, Set<SpotType> compatibleSpotTypes) {
        this.requiredSpots = requiredSpots;
        this.compatibleSpotTypes = compatibleSpotTypes;
    }

    public int getRequiredSpots()              { return requiredSpots; }
    public Set<SpotType> getCompatibleSpotTypes() { return compatibleSpotTypes; }
    public boolean isMultiSpot()               { return requiredSpots > 1; }

    public boolean fitsIn(SpotType spotType) {
        return compatibleSpotTypes.contains(spotType);
    }
}
