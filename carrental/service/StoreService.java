package carrental.service;

import carrental.enums.VehicleType;
import carrental.model.DateRange;
import carrental.model.Store;
import carrental.model.Vehicle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of stores and their vehicles.
 * Provides availability search — combines vehicle status and date-range reservation check.
 */
public class StoreService {
    private final Map<String, Store> storesById = new ConcurrentHashMap<>();
    private final Map<String, Vehicle> vehiclesById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> vehiclesByStoreId = new ConcurrentHashMap<>();

    private final AvailabilityIndex availabilityIndex;

    public StoreService(AvailabilityIndex availabilityIndex) {
        this.availabilityIndex = availabilityIndex;
    }

    public void addStore(Store store) {
        storesById.put(store.getStoreId(), store);
        vehiclesByStoreId.computeIfAbsent(store.getStoreId(), k -> ConcurrentHashMap.newKeySet());
    }

    public void addVehicleToStore(Vehicle vehicle) {
        vehiclesById.put(vehicle.getVehicleId(), vehicle);
        vehiclesByStoreId
            .computeIfAbsent(vehicle.getCurrentStoreId(), k -> ConcurrentHashMap.newKeySet())
            .add(vehicle.getVehicleId());
    }

    public Store getStore(String storeId) {
        Store store = storesById.get(storeId);
        if (store == null) throw new IllegalArgumentException("Store not found: " + storeId);
        return store;
    }

    public Vehicle getVehicle(String vehicleId) {
        Vehicle vehicle = vehiclesById.get(vehicleId);
        if (vehicle == null) throw new IllegalArgumentException("Vehicle not found: " + vehicleId);
        return vehicle;
    }

    /** Search vehicles at a given store of a given type, available for the date range. */
    public List<Vehicle> searchAvailable(String storeId, VehicleType type, DateRange dateRange) {
        Set<String> vehicleIds = vehiclesByStoreId.getOrDefault(storeId, Collections.emptySet());
        return vehicleIds.stream()
            .map(vehiclesById::get)
            .filter(Objects::nonNull)
            .filter(Vehicle::isRentable)
            .filter(v -> v.getType() == type)
            .filter(v -> availabilityIndex.isAvailable(v.getVehicleId(), dateRange))
            .collect(Collectors.toList());
    }

    public int getAvailableCount(String storeId, VehicleType type, DateRange dateRange) {
        return searchAvailable(storeId, type, dateRange).size();
    }
}
