package locker.strategy;

import locker.enums.PackageSize;
import locker.model.Locker;
import locker.model.LockerLocation;

import java.util.Optional;

public interface LockerAllocationStrategy {
    Optional<Locker> findLocker(LockerLocation location, PackageSize packageSize);
}
