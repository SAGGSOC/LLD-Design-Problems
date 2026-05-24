package locker.model;

import locker.enums.LockerSize;
import locker.enums.PackageSize;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class LockerLocation {
    private final String locationId;
    private final String name;
    private final String address;
    private final double latitude;
    private final double longitude;
    private final List<Locker> lockers;
    private final Map<String, Locker> lockerById;

    public LockerLocation(String locationId, String name, String address,
                          double latitude, double longitude, List<Locker> lockers) {
        this.locationId = locationId;
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.lockers = lockers;
        this.lockerById = new ConcurrentHashMap<>();
        for (Locker locker : lockers) {
            lockerById.put(locker.getLockerId(), locker);
        }
    }

    public Locker getLocker(String lockerId) {
        return lockerById.get(lockerId);
    }

    public List<Locker> getAvailableLockers(LockerSize size) {
        return lockers.stream()
            .filter(locker -> locker.getSize() == size && locker.isAvailable())
            .collect(Collectors.toList());
    }

    public int getAvailableCount(LockerSize size) {
        return (int) lockers.stream()
            .filter(locker -> locker.getSize() == size && locker.isAvailable())
            .count();
    }

    public boolean hasCapacity(PackageSize packageSize) {
        return lockers.stream()
            .anyMatch(locker -> locker.canFit(packageSize) && locker.isAvailable());
    }

    /** Haversine distance in km — for "find nearby" queries. */
    public double distanceKm(double otherLat, double otherLng) {
        double R = 6371;  // earth radius in km
        double dLat = Math.toRadians(otherLat - this.latitude);
        double dLng = Math.toRadians(otherLng - this.longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(this.latitude)) * Math.cos(Math.toRadians(otherLat))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    public String getLocationId()    { return locationId; }
    public String getName()          { return name; }
    public String getAddress()       { return address; }
    public List<Locker> getLockers() { return lockers; }
}
