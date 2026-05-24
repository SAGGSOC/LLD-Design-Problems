package locker.strategy;

import locker.enums.LockerSize;
import locker.enums.PackageSize;
import locker.model.Locker;
import locker.model.LockerLocation;

import java.util.Optional;

/**
 * Allocates the smallest available locker that fits the package.
 * Upsizes to the next size if exact fit is unavailable.
 * Example: MEDIUM package → try MEDIUM → LARGE → XL.
 */
public class SmallestFitStrategy implements LockerAllocationStrategy {

    @Override
    public Optional<Locker> findLocker(LockerLocation location, PackageSize packageSize) {
        // Sizes ordered smallest to largest — try each starting from exact match
        LockerSize[] allSizes = LockerSize.values();
        int startIndex = packageSize.ordinal();

        for (int sizeIndex = startIndex; sizeIndex < allSizes.length; sizeIndex++) {
            LockerSize candidateSize = allSizes[sizeIndex];
            Optional<Locker> availableLocker = location.getAvailableLockers(candidateSize)
                .stream()
                .findFirst();
            if (availableLocker.isPresent()) {
                return availableLocker;
            }
        }
        return Optional.empty();
    }
}
