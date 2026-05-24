package locker.service;

import locker.enums.ReservationStatus;
import locker.exception.LockerException;
import locker.model.Locker;
import locker.model.LockerLocation;
import locker.model.Reservation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Handles delivery agent flow: unlock → deposit → generate OTP → notify customer.
 */
public class DepositService {
    private static final int PICKUP_WINDOW_HOURS = 72;

    private final ReservationService reservationService;
    private final LockerLocationService locationService;
    private final OtpService otpService;
    private final NotificationService notificationService;

    public DepositService(ReservationService reservationService,
                          LockerLocationService locationService,
                          OtpService otpService,
                          NotificationService notificationService) {
        this.reservationService = reservationService;
        this.locationService = locationService;
        this.otpService = otpService;
        this.notificationService = notificationService;
    }

    public DepositResult depositPackage(String reservationId, String agentId) {
        Reservation reservation = reservationService.getReservation(reservationId);
        if (reservation.getStatus() != ReservationStatus.CREATED) {
            throw new LockerException(
                "Cannot deposit — reservation in status " + reservation.getStatus());
        }

        Locker locker = locationService.getLocker(reservation.getLockerId());
        LockerLocation location = locationService.getLocationForLocker(locker.getLockerId());

        // 1. Unlock physical locker
        LockerHardware.unlock(locker.getLockerId());

        // 2. Agent deposits. In production, a door sensor confirms closure.

        // 3. Mark locker OCCUPIED
        locker.markDeposited();

        // 4. Update reservation
        Instant now = Instant.now();
        reservation.setStatus(ReservationStatus.DEPOSITED);
        reservation.setDepositedAt(now);
        reservation.setExpiresAt(now.plus(PICKUP_WINDOW_HOURS, ChronoUnit.HOURS));

        // 5. Generate OTP (plaintext returned once — for notification only)
        String plainOtp = otpService.generateOtp(
            reservationId, locker.getLockerId(), reservation.getCustomerId());

        // 6. Notify customer via all channels
        notificationService.sendDepositNotification(
            reservation.getCustomerId(),
            locker.getLockerId(),
            location.getName(),
            plainOtp
        );

        return new DepositResult(reservationId, locker.getLockerId(), now, plainOtp);
    }

    public static class DepositResult {
        public final String reservationId;
        public final String lockerId;
        public final Instant depositedAt;
        public final String otpForTesting;  // ONLY for testing/demo. In prod, never expose this.

        public DepositResult(String reservationId, String lockerId,
                             Instant depositedAt, String otpForTesting) {
            this.reservationId = reservationId;
            this.lockerId = lockerId;
            this.depositedAt = depositedAt;
            this.otpForTesting = otpForTesting;
        }
    }
}
