package locker.service;

import locker.exception.LockerNotFoundException;
import locker.model.Locker;
import locker.model.LockerLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class LockerLocationService {
    private final Map<String, LockerLocation> locationsById = new ConcurrentHashMap<>();
    // Global lockerId → location index for O(1) cross-location lookup
    private final Map<String, LockerLocation> locationByLockerId = new ConcurrentHashMap<>();

    public void addLocation(LockerLocation location) {
        locationsById.put(location.getLocationId(), location);
        for (Locker locker : location.getLockers()) {
            locationByLockerId.put(locker.getLockerId(), location);
        }
    }

    public LockerLocation getLocation(String locationId) {
        return locationsById.get(locationId);
    }

    public Collection<LockerLocation> getAllLocations() {
        return locationsById.values();
    }

    /** O(1) global locker lookup. */
    public Locker getLocker(String lockerId) {
        LockerLocation location = locationByLockerId.get(lockerId);
        if (location == null) throw new LockerNotFoundException(lockerId);
        return location.getLocker(lockerId);
    }

    /** Find the location that contains a given locker. */
    public LockerLocation getLocationForLocker(String lockerId) {
        LockerLocation location = locationByLockerId.get(lockerId);
        if (location == null) throw new LockerNotFoundException(lockerId);
        return location;
    }

    /** Find all locker locations within radiusKm of given coordinates, sorted nearest first. */
    public List<LockerLocation> findNearby(double latitude, double longitude, double radiusKm) {
        return locationsById.values().stream()
            .filter(location -> location.distanceKm(latitude, longitude) <= radiusKm)
            .sorted(Comparator.comparingDouble(location ->
                location.distanceKm(latitude, longitude)))
            .collect(Collectors.toList());
    }
}
