package parkinglot.model;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

/** Large spot — fits motorcycles, cars, and trucks. Buses use multiple of these. */
public class LargeParkingSpot extends ParkingSpot {

    public LargeParkingSpot(String spotId, int floorNumber, int spotNumber,
                            boolean isHandicap, boolean hasEVCharging) {
        super(spotId, floorNumber, spotNumber, SpotType.LARGE, isHandicap, hasEVCharging);
    }

    @Override
    public boolean canFit(VehicleType vehicleType) {
        if (!isAvailable()) return false;
        // Fits everything except BUS (bus needs multiple consecutive large spots)
        return vehicleType == VehicleType.MOTORCYCLE
            || vehicleType == VehicleType.CAR
            || vehicleType == VehicleType.TRUCK;
    }
}
