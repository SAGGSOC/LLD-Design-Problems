package parkinglot.factory;

import parkinglot.enums.SpotType;
import parkinglot.model.*;

/**
 * Factory for creating ParkingSpot instances.
 * Callers pass SpotType — factory returns the correct concrete subclass.
 * Adding a new spot type only requires a new case here + a new subclass.
 */
public class ParkingSpotFactory {

    public static ParkingSpot createSpot(SpotType spotType, String spotId,
                                          int floorNumber, int spotNumber,
                                          boolean isHandicap, boolean hasEVCharging) {
        switch (spotType) {
            case SMALL:
                return new MotorcycleParkingSpot(spotId, floorNumber, spotNumber,
                                                  isHandicap, hasEVCharging);
            case MEDIUM:
                return new CarParkingSpot(spotId, floorNumber, spotNumber,
                                          isHandicap, hasEVCharging);
            case LARGE:
                return new LargeParkingSpot(spotId, floorNumber, spotNumber,
                                            isHandicap, hasEVCharging);
            default:
                throw new IllegalArgumentException("Unknown spot type: " + spotType);
        }
    }
}
