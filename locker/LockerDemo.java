package locker;

import locker.enums.LockerSize;
import locker.enums.PackageSize;
import locker.model.Locker;
import locker.model.LockerLocation;
import locker.model.Package;
import locker.model.Reservation;
import locker.service.*;
import locker.strategy.SmallestFitStrategy;

import java.util.ArrayList;
import java.util.List;

public class LockerDemo {

    public static void main(String[] args) {
        // ─── Setup ───
        NotificationService notifications = new NotificationService();
        OtpService otpService = new OtpService();
        LockerLocationService locationService = new LockerLocationService();

        // Build a location with 4 lockers of each size
        locationService.addLocation(buildDowntownLocation());
        locationService.addLocation(buildAirportLocation());

        ReservationService reservations = new ReservationService(
            locationService, new SmallestFitStrategy());
        DepositService deposits = new DepositService(
            reservations, locationService, otpService, notifications);
        RetrievalService retrievals = new RetrievalService(
            otpService, locationService, reservations, notifications);

        // ─── Scenario 1: Happy path ───
        System.out.println("=== Scenario 1: Happy Path ===");
        Package pkg = new Package("PKG-001", "ORD-999", PackageSize.MEDIUM, 2.5);
        Reservation res = reservations.reserve("LOC-downtown", "cust-42", pkg);
        System.out.println("Reserved: " + res.getReservationId()
            + " @ locker " + res.getLockerId());

        DepositService.DepositResult depositResult =
            deposits.depositPackage(res.getReservationId(), "agent-007");
        System.out.println("Deposited at: " + depositResult.depositedAt);

        // Customer reads the OTP from their SMS/email notification
        String otpFromNotification = depositResult.otpForTesting;
        System.out.println("Customer received OTP: " + otpFromNotification);

        RetrievalService.RetrievalResult retrieveResult =
            retrievals.retrievePackage(res.getLockerId(), otpFromNotification);
        System.out.println("Retrieval success? " + retrieveResult.success
            + " | Package: " + retrieveResult.packageId);

        System.out.println();

        // ─── Scenario 2: Wrong OTP 3x → lockout ───
        System.out.println("=== Scenario 2: Brute Force Lockout ===");
        Package pkg2 = new Package("PKG-002", "ORD-1000", PackageSize.SMALL, 0.5);
        Reservation res2 = reservations.reserve("LOC-downtown", "cust-99", pkg2);
        deposits.depositPackage(res2.getReservationId(), "agent-007");

        for (int attempt = 1; attempt <= 4; attempt++) {
            RetrievalService.RetrievalResult result =
                retrievals.retrievePackage(res2.getLockerId(), "000000");
            System.out.println("Attempt " + attempt + ": " + result.errorMessage
                + " (remaining=" + result.remainingAttempts + ")");
            if (result.remainingAttempts == 0) break;
        }

        System.out.println();

        // ─── Scenario 3: Upsizing when exact fit unavailable ───
        System.out.println("=== Scenario 3: Upsize (all SMALL taken) ===");
        // Fill up all SMALL lockers at downtown
        LockerLocation downtown = locationService.getLocation("LOC-downtown");
        List<Locker> smallLockers = downtown.getAvailableLockers(LockerSize.SMALL);
        System.out.println("SMALL lockers available before: " + smallLockers.size());
        // Reserve all remaining SMALL
        for (int i = 0; i < smallLockers.size(); i++) {
            Package filler = new Package("FILL-" + i, "ORD-F-" + i,
                PackageSize.SMALL, 0.1);
            reservations.reserve("LOC-downtown", "cust-filler-" + i, filler);
        }
        System.out.println("SMALL lockers available after: "
            + downtown.getAvailableCount(LockerSize.SMALL));

        // Now a SMALL package comes — should upsize to MEDIUM
        Package smallPkg = new Package("PKG-003", "ORD-1001",
            PackageSize.SMALL, 0.3);
        Reservation res3 = reservations.reserve("LOC-downtown", "cust-55", smallPkg);
        Locker assignedLocker = locationService.getLocker(res3.getLockerId());
        System.out.println("SMALL package assigned to " + assignedLocker.getSize()
            + " locker: " + res3.getLockerId());

        System.out.println();

        // ─── Scenario 4: Find nearby locations ───
        System.out.println("=== Scenario 4: Find Nearby ===");
        List<LockerLocation> nearby = locationService.findNearby(47.6, -122.3, 20.0);
        for (LockerLocation location : nearby) {
            double dist = location.distanceKm(47.6, -122.3);
            System.out.printf("  %-25s %.2f km  (%d MEDIUM available)%n",
                location.getName(), dist,
                location.getAvailableCount(LockerSize.MEDIUM));
        }
    }

    private static LockerLocation buildDowntownLocation() {
        List<Locker> lockers = new ArrayList<>();
        int lockerNum = 1;
        for (LockerSize size : LockerSize.values()) {
            for (int i = 0; i < 4; i++) {
                lockers.add(new Locker("LKR-DT-" + lockerNum++,
                    "LOC-downtown", size));
            }
        }
        return new LockerLocation("LOC-downtown", "Seattle Downtown",
            "101 Main St, Seattle", 47.6062, -122.3321, lockers);
    }

    private static LockerLocation buildAirportLocation() {
        List<Locker> lockers = new ArrayList<>();
        int lockerNum = 1;
        for (LockerSize size : LockerSize.values()) {
            for (int i = 0; i < 4; i++) {
                lockers.add(new Locker("LKR-AP-" + lockerNum++,
                    "LOC-airport", size));
            }
        }
        return new LockerLocation("LOC-airport", "SeaTac Airport",
            "17801 International Blvd, SeaTac", 47.4502, -122.3088, lockers);
    }
}
