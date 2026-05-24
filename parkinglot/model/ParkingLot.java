package parkinglot.model;

import parkinglot.enums.VehicleType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ParkingLot {
    private final String name;
    private final List<ParkingFloor> floors;
    private final int totalCapacity;

    // O(1) lookup: licensePlate → spot where the vehicle is parked
    private final Map<String, ParkingSpot> vehicleLocationMap = new ConcurrentHashMap<>();

    public ParkingLot(String name, List<ParkingFloor> floors) {
        this.name = name;
        this.floors = floors;
        this.totalCapacity = floors.stream()
                .mapToInt(floor -> floor.getSpots().size()).sum();
    }

    /** Called by EntryGate after a vehicle is assigned to a spot. */
    public void registerVehicleLocation(String licensePlate, ParkingSpot spot) {
        vehicleLocationMap.put(licensePlate, spot);
    }

    /** Called by ExitGate when a vehicle leaves. */
    public void unregisterVehicleLocation(String licensePlate) {
        vehicleLocationMap.remove(licensePlate);
    }

    /** O(1) vehicle lookup by license plate. */
    public Optional<ParkingSpot> findVehicle(String licensePlate) {
        return Optional.ofNullable(vehicleLocationMap.get(licensePlate));
    }

    public int getAvailableCount(VehicleType vehicleType) {
        return floors.stream()
                .mapToInt(floor -> floor.getAvailableCount(vehicleType)).sum();
    }

    public boolean isFull(VehicleType vehicleType) {
        return getAvailableCount(vehicleType) == 0;
    }

    public OccupancyStats getOccupancyRate() {
        int occupiedCount = (int) floors.stream()
                .flatMap(floor -> floor.getSpots().stream())
                .filter(spot -> !spot.isAvailable()).count();
        return new OccupancyStats(totalCapacity, occupiedCount, totalCapacity - occupiedCount);
    }

    public String getName()                  { return name; }
    public List<ParkingFloor> getFloors()    { return floors; }
    public int getTotalCapacity()            { return totalCapacity; }
    public int getParkedVehicleCount()       { return vehicleLocationMap.size(); }
}
