package parkinglot.model;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class ParkingFloor {
    private final int floorNumber;
    private final List<ParkingSpot> spots;
    private final ReentrantLock floorLock = new ReentrantLock();

    public ParkingFloor(int floorNumber, List<ParkingSpot> spots) {
        this.floorNumber = floorNumber;
        this.spots = spots;
    }

    public List<ParkingSpot> getAvailableSpots(VehicleType vehicleType) {
        return spots.stream()
                .filter(spot -> spot.canFit(vehicleType))
                .collect(Collectors.toList());
    }

    public int getAvailableCount(VehicleType vehicleType) {
        if (vehicleType.isMultiSpot()) {
            return countConsecutiveGroups(vehicleType.getRequiredSpots(),
                                         vehicleType.getCompatibleSpotTypes());
        }
        return (int) spots.stream().filter(spot -> spot.canFit(vehicleType)).count();
    }

    /**
     * Finds N consecutive available spots of compatible types on this floor.
     * Generic — works for any multi-spot vehicle type, not just buses.
     */
    public Optional<List<ParkingSpot>> findConsecutiveSpots(int requiredCount,
                                                             VehicleType vehicleType) {
        Set<SpotType> compatibleTypes = vehicleType.getCompatibleSpotTypes();

        List<ParkingSpot> compatibleAvailableSpots = spots.stream()
                .filter(spot -> spot.isAvailable() && compatibleTypes.contains(spot.getSpotType()))
                .sorted((spotA, spotB) -> Integer.compare(spotA.getSpotNumber(), spotB.getSpotNumber()))
                .collect(Collectors.toList());

        for (int startIndex = 0; startIndex <= compatibleAvailableSpots.size() - requiredCount; startIndex++) {
            List<ParkingSpot> candidateGroup = new ArrayList<>();
            candidateGroup.add(compatibleAvailableSpots.get(startIndex));
            boolean isConsecutive = true;

            for (int offset = 1; offset < requiredCount; offset++) {
                ParkingSpot previousSpot = compatibleAvailableSpots.get(startIndex + offset - 1);
                ParkingSpot currentSpot = compatibleAvailableSpots.get(startIndex + offset);
                if (currentSpot.getSpotNumber() != previousSpot.getSpotNumber() + 1) {
                    isConsecutive = false;
                    break;
                }
                candidateGroup.add(currentSpot);
            }

            if (isConsecutive) return Optional.of(candidateGroup);
        }
        return Optional.empty();
    }

    /**
     * Atomically assigns a vehicle to multiple consecutive spots.
     * Uses floor-level ReentrantLock to prevent races.
     * Generic — works for any multi-spot vehicle, not just buses.
     */
    public List<ParkingSpot> assignMultiSpot(Vehicle vehicle, VehicleType vehicleType) {
        floorLock.lock();
        try {
            Optional<List<ParkingSpot>> consecutiveSpots =
                    findConsecutiveSpots(vehicleType.getRequiredSpots(), vehicleType);
            if (consecutiveSpots.isEmpty()) return null;

            List<ParkingSpot> assignedSpots = consecutiveSpots.get();
            for (ParkingSpot spot : assignedSpots) {
                spot.assignVehicle(vehicle);
            }
            return assignedSpots;
        } finally {
            floorLock.unlock();
        }
    }

    public Optional<ParkingSpot> findVehicle(String licensePlate) {
        return spots.stream()
                .filter(spot -> spot.getVehicle() != null
                        && spot.getVehicle().getLicensePlate().equals(licensePlate))
                .findFirst();
    }

    public int getFloorNumber()             { return floorNumber; }
    public List<ParkingSpot> getSpots()     { return spots; }

    private int countConsecutiveGroups(int groupSize, Set<SpotType> compatibleTypes) {
        List<ParkingSpot> compatibleAvailableSpots = spots.stream()
                .filter(spot -> spot.isAvailable() && compatibleTypes.contains(spot.getSpotType()))
                .sorted((spotA, spotB) -> Integer.compare(spotA.getSpotNumber(), spotB.getSpotNumber()))
                .collect(Collectors.toList());

        int groupCount = 0;
        for (int startIndex = 0; startIndex <= compatibleAvailableSpots.size() - groupSize; startIndex++) {
            boolean isConsecutive = true;
            for (int offset = 1; offset < groupSize; offset++) {
                if (compatibleAvailableSpots.get(startIndex + offset).getSpotNumber()
                        != compatibleAvailableSpots.get(startIndex + offset - 1).getSpotNumber() + 1) {
                    isConsecutive = false;
                    break;
                }
            }
            if (isConsecutive) groupCount++;
        }
        return groupCount;
    }
}
