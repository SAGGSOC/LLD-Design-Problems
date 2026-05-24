package parkinglot.strategy;

import parkinglot.enums.VehicleType;
import parkinglot.model.ParkingFloor;
import parkinglot.model.ParkingLot;
import parkinglot.model.ParkingSpot;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Assigns the nearest available spot(s) — lowest floor, lowest spot number.
 * Handles both single-spot and multi-spot vehicles uniformly.
 */
public class NearestFirstStrategy implements SpotAssignmentStrategy {

    @Override
    public Optional<List<ParkingSpot>> findSpots(ParkingLot parkingLot, VehicleType vehicleType) {
        if (vehicleType.isMultiSpot()) {
            return findConsecutiveSpots(parkingLot, vehicleType);
        }
        return findSingleSpot(parkingLot, vehicleType);
    }

    private Optional<List<ParkingSpot>> findSingleSpot(ParkingLot parkingLot, VehicleType vehicleType) {
        for (ParkingFloor floor : parkingLot.getFloors()) {
            Optional<ParkingSpot> nearestSpot = floor.getAvailableSpots(vehicleType).stream()
                    .min(Comparator.comparingInt(ParkingSpot::getSpotNumber));
            if (nearestSpot.isPresent()) {
                return Optional.of(List.of(nearestSpot.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<List<ParkingSpot>> findConsecutiveSpots(ParkingLot parkingLot, VehicleType vehicleType) {
        int requiredSpots = vehicleType.getRequiredSpots();
        for (ParkingFloor floor : parkingLot.getFloors()) {
            Optional<List<ParkingSpot>> consecutiveSpots =
                    floor.findConsecutiveSpots(requiredSpots, vehicleType);
            if (consecutiveSpots.isPresent()) {
                return consecutiveSpots;
            }
        }
        return Optional.empty();
    }
}
