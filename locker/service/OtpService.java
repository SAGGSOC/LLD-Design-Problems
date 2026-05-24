package locker.service;

import locker.exception.InvalidOtpException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates and verifies time-bound OTPs for locker retrieval.
 * Security properties:
 *  - 6-digit OTP via SecureRandom (CSPRNG)
 *  - SHA-256 hashed storage (in real prod: bcrypt with cost factor)
 *  - Single-use — invalidated after successful verification
 *  - Time-bound — 72h default validity
 *  - Replay-safe — used flag checked
 *
 * Note: Brute-force protection (3-attempt limit) lives on the Locker
 * entity itself (Locker.recordFailedAttempt), not here.
 */
public class OtpService {
    private static final int OTP_LENGTH = 6;
    private static final int DEFAULT_VALIDITY_HOURS = 72;

    private final Map<String, OtpEntry> otpByLockerId = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    /** Returns the plaintext OTP — only time it exists unencrypted. */
    public String generateOtp(String reservationId, String lockerId, String customerId) {
        String plainOtp = generateSecureSixDigit();
        String otpHash = hash(plainOtp);

        Instant now = Instant.now();
        OtpEntry entry = new OtpEntry(
            otpHash, reservationId, lockerId, customerId,
            now, now.plus(DEFAULT_VALIDITY_HOURS, ChronoUnit.HOURS)
        );
        otpByLockerId.put(lockerId, entry);
        return plainOtp;
    }

    public boolean verifyOtp(String lockerId, String inputOtp) {
        OtpEntry entry = otpByLockerId.get(lockerId);
        if (entry == null) {
            throw new InvalidOtpException("No active OTP for locker " + lockerId);
        }
        if (entry.isUsed()) {
            throw new InvalidOtpException("OTP already used");
        }
        if (Instant.now().isAfter(entry.getExpiresAt())) {
            throw new InvalidOtpException("OTP expired");
        }

        boolean matches = constantTimeEquals(hash(inputOtp), entry.getOtpHash());
        if (matches) {
            entry.markUsed();  // single-use
        }
        return matches;
    }

    public void invalidate(String lockerId) {
        otpByLockerId.remove(lockerId);
    }

    private String generateSecureSixDigit() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }

    private String hash(String plain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(plain.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hash failed", e);
        }
    }

    /** Prevents timing attacks on hash comparison. */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /** Internal OTP record — stored server-side only. */
    private static class OtpEntry {
        private final String otpHash;
        private final String reservationId;
        private final String lockerId;
        private final String customerId;
        private final Instant generatedAt;
        private final Instant expiresAt;
        private boolean used;

        OtpEntry(String otpHash, String reservationId, String lockerId,
                 String customerId, Instant generatedAt, Instant expiresAt) {
            this.otpHash = otpHash;
            this.reservationId = reservationId;
            this.lockerId = lockerId;
            this.customerId = customerId;
            this.generatedAt = generatedAt;
            this.expiresAt = expiresAt;
            this.used = false;
        }

        String getOtpHash()       { return otpHash; }
        Instant getExpiresAt()    { return expiresAt; }
        boolean isUsed()          { return used; }
        void markUsed()           { this.used = true; }
    }
}
