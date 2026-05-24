package parkinglot.strategy;

import parkinglot.enums.VehicleType;
import parkinglot.model.ParkingLot;
import parkinglot.model.ParkingSpot;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for assigning spots to vehicles.
 * Handles both single-spot and multi-spot vehicles uniformly.
 *
 * Returns a List<ParkingSpot> — size 1 for cars, size N for buses.
 * Empty optional means no spots available.
 */
public interface SpotAssignmentStrategy {

    Optional<List<ParkingSpot>> findSpots(ParkingLot parkingLot, VehicleType vehicleType);
}
