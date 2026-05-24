package locker.service;

import locker.exception.NoLockerAvailableException;
import locker.exception.ReservationNotFoundException;
import locker.model.Locker;
import locker.model.LockerLocation;
import locker.model.Package;
import locker.model.Reservation;
import locker.strategy.LockerAllocationStrategy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public class ReservationService {
    private final Map<String, Reservation> reservationsById = new ConcurrentHashMap<>();
    private final LockerLocationService locationService;
    private final LockerAllocationStrategy allocationStrategy;

    public ReservationService(LockerLocationService locationService,
                              LockerAllocationStrategy allocationStrategy) {
        this.locationService = locationService;
        this.allocationStrategy = allocationStrategy;
    }

    public Reservation reserve(String locationId, String customerId, Package pkg) {
        LockerLocation location = locationService.getLocation(locationId);
        if (location == null) {
            throw new NoLockerAvailableException("Location not found: " + locationId);
        }

        Optional<Locker> selectedLocker = allocationStrategy.findLocker(location, pkg.getSize());
        if (selectedLocker.isEmpty()) {
            throw new NoLockerAvailableException(
                "No " + pkg.getSize() + " locker available at " + locationId);
        }

        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        Reservation reservation = new Reservation(
            reservationId, pkg.getOrderId(), customerId,
            selectedLocker.get().getLockerId(), pkg.getPackageId()
        );

        // Atomic reservation — synchronized inside Locker
        selectedLocker.get().reserve(reservationId);
        reservationsById.put(reservationId, reservation);
        return reservation;
    }

    public Reservation getReservation(String reservationId) {
        Reservation reservation = reservationsById.get(reservationId);
        if (reservation == null) throw new ReservationNotFoundException(reservationId);
        return reservation;
    }
}
