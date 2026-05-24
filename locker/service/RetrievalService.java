package locker.service;

import locker.enums.LockerStatus;
import locker.enums.ReservationStatus;
import locker.exception.LockerException;
import locker.model.Locker;
import locker.model.Reservation;

import java.time.Instant;

/**
 * Handles customer pickup: verify OTP → unlock → mark retrieved.
 * Enforces brute-force protection via Locker.recordFailedAttempt().
 */
public class RetrievalService {
    private final OtpService otpService;
    private final LockerLocationService locationService;
    private final ReservationService reservationService;
    private final NotificationService notificationService;

    public RetrievalService(OtpService otpService,
                            LockerLocationService locationService,
                            ReservationService reservationService,
                            NotificationService notificationService) {
        this.otpService = otpService;
        this.locationService = locationService;
        this.reservationService = reservationService;
        this.notificationService = notificationService;
    }

    public RetrievalResult retrievePackage(String lockerId, String inputOtp) {
        Locker locker = locationService.getLocker(lockerId);

        if (locker.getStatus() != LockerStatus.OCCUPIED) {
            throw new LockerException(
                "Locker " + lockerId + " is not awaiting pickup (status=" + locker.getStatus() + ")");
        }

        boolean otpValid;
        try {
            otpValid = otpService.verifyOtp(lockerId, inputOtp);
        } catch (Exception e) {
            // OTP expired or already used — treat as failure
            boolean lockedOut = locker.recordFailedAttempt();
            if (lockedOut) notificationService.sendSecurityAlert(lockerId);
            return RetrievalResult.failure(e.getMessage(), locker.getRemainingAttempts());
        }

        if (!otpValid) {
            boolean lockedOut = locker.recordFailedAttempt();
            if (lockedOut) {
                notificationService.sendSecurityAlert(lockerId);
                return RetrievalResult.failure(
                    "Too many failed attempts — locker locked. Contact support.",
                    0);
            }
            return RetrievalResult.failure(
                "Invalid OTP", locker.getRemainingAttempts());
        }

        // OTP verified — unlock the locker
        LockerHardware.unlock(lockerId);

        Reservation reservation = reservationService.getReservation(
            locker.getCurrentReservationId());
        String packageId = reservation.getPackageId();
        String customerId = reservation.getCustomerId();

        // Mark retrieved
        reservation.setStatus(ReservationStatus.RETRIEVED);
        reservation.setRetrievedAt(Instant.now());
        locker.markRetrieved();

        // Invalidate OTP to prevent reuse
        otpService.invalidate(lockerId);

        // Send receipt
        notificationService.sendPickupReceipt(customerId, packageId);

        return RetrievalResult.success(packageId);
    }

    public static class RetrievalResult {
        public final boolean success;
        public final String packageId;
        public final String errorMessage;
        public final int remainingAttempts;

        private RetrievalResult(boolean success, String packageId,
                                String errorMessage, int remainingAttempts) {
            this.success = success;
            this.packageId = packageId;
            this.errorMessage = errorMessage;
            this.remainingAttempts = remainingAttempts;
        }

        public static RetrievalResult success(String packageId) {
            return new RetrievalResult(true, packageId, null, 0);
        }

        public static RetrievalResult failure(String error, int remainingAttempts) {
            return new RetrievalResult(false, null, error, remainingAttempts);
        }
    }
}
