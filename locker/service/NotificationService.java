package locker.service;

/**
 * Simulated notification service — prints to stdout.
 * In production: push via SNS, SMS via AWS Pinpoint, email via SES.
 */
public class NotificationService {

    public void sendDepositNotification(String customerId, String lockerId,
                                        String locationName, String plainOtp) {
        System.out.println("[NOTIFY] Customer " + customerId
            + " — your package is ready at " + locationName
            + " | Locker " + lockerId + " | OTP: " + plainOtp);
    }

    public void sendPickupReceipt(String customerId, String packageId) {
        System.out.println("[NOTIFY] Customer " + customerId
            + " — package " + packageId + " picked up successfully.");
    }

    public void sendExpiryWarning(String customerId, String lockerId) {
        System.out.println("[NOTIFY] Customer " + customerId
            + " — package at " + lockerId + " expires in 24h. Please pick up.");
    }

    public void sendSecurityAlert(String lockerId) {
        System.out.println("[SECURITY] Locker " + lockerId
            + " locked out after repeated failed OTP attempts.");
    }
}
