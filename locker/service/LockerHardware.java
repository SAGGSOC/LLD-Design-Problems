package locker.service;

/**
 * Stub for physical locker hardware integration.
 * In production: calls an IoT service that controls the physical door mechanism.
 */
public class LockerHardware {

    public static void unlock(String lockerId) {
        System.out.println("[HARDWARE] Unlocking locker " + lockerId);
        // Real: send IoT command via MQTT/HTTP to the locker controller
    }

    public static void lock(String lockerId) {
        System.out.println("[HARDWARE] Locking locker " + lockerId);
    }
}
