package parkinglot.model;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

/** Small spot — fits motorcycles only. */
public class MotorcycleParkingSpot extends ParkingSpot {

    public MotorcycleParkingSpot(String spotId, int floorNumber, int spotNumber,
                                  boolean isHandicap, boolean hasEVCharging) {
        super(spotId, floorNumber, spotNumber, SpotType.SMALL, isHandicap, hasEVCharging);
    }

    @Override
    public boolean canFit(VehicleType vehicleType) {
        return isAvailable() && vehicleType == VehicleType.MOTORCYCLE;
    }
}
