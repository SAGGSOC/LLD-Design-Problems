package parkinglot.model;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

/** Medium spot — fits motorcycles and cars. */
public class CarParkingSpot extends ParkingSpot {

    public CarParkingSpot(String spotId, int floorNumber, int spotNumber,
                          boolean isHandicap, boolean hasEVCharging) {
        super(spotId, floorNumber, spotNumber, SpotType.MEDIUM, isHandicap, hasEVCharging);
    }

    @Override
    public boolean canFit(VehicleType vehicleType) {
        return isAvailable()
            && (vehicleType == VehicleType.MOTORCYCLE || vehicleType == VehicleType.CAR);
    }
}
