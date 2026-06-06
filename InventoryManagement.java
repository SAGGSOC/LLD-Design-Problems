import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory Management System — Full Interview Version
 *
 * Core APIs:
 *   - addStock / removeStock / checkAvailability
 *   - transfer (atomic with deadlock prevention)
 *   - setLowStockAlert (Observer pattern, boundary crossing)
 *
 * Advanced Follow-ups:
 *   - Reservation system (prevent overselling during checkout)
 *   - Transfer as InventoryHolder (in-transit stock tracking)
 *
 * Concurrency:
 *   - Coarse-grained: synchronized on Warehouse (interview default)
 *   - Fine-grained: per-product locks (mentioned as optimization)
 *   - Alerts fired OUTSIDE lock (avoid holding lock during I/O)
 */
public class InventoryManagement {

    // ═══════════════════════════════════════════════
    // Observer Interface
    // ═══════════════════════════════════════════════
    interface AlertListener {
        void onLowStock(String warehouseId, String productId, int currentQuantity);
    }

    static class AlertConfig {
        private final int threshold;
        private final AlertListener listener;

        public AlertConfig(int threshold, AlertListener listener) {
            this.threshold = threshold;
            this.listener = listener;
        }

        public int getThreshold() { return threshold; }
        public AlertListener getListener() { return listener; }
    }

    // ═══════════════════════════════════════════════
    // InventoryHolder Interface (for Transfer pattern)
    // ═══════════════════════════════════════════════
    interface InventoryHolder {
        void addStock(String productId, int quantity);
        boolean removeStock(String productId, int quantity);
        int getStock(String productId);
        boolean checkAvailability(String productId, int quantity);
    }

    // ═══════════════════════════════════════════════
    // Reservation (for overselling prevention)
    // ═══════════════════════════════════════════════
    static class Reservation {
        private final String reservationId;
        private final String productId;
        private final int quantity;
        private final long expiresAt;

        public Reservation(String reservationId, String productId, int quantity, long expiresAt) {
            this.reservationId = reservationId;
            this.productId = productId;
            this.quantity = quantity;
            this.expiresAt = expiresAt;
        }

        public String getReservationId() { return reservationId; }
        public String getProductId() { return productId; }
        public int getQuantity() { return quantity; }
        public long getExpiresAt() { return expiresAt; }
        public boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    // ═══════════════════════════════════════════════
    // Warehouse (implements InventoryHolder)
    // ═══════════════════════════════════════════════
    static class Warehouse implements InventoryHolder {
        private final String id;
        private final Map<String, Integer> inventory;               // productId → physical qty
        private final Map<String, Integer> reserved;                // productId → reserved qty
        private final Map<String, Reservation> reservations;        // reservationId → Reservation
        private final Map<String, List<AlertConfig>> alertConfigs;

        public Warehouse(String id) {
            this.id = id;
            this.inventory = new HashMap<>();
            this.reserved = new HashMap<>();
            this.reservations = new HashMap<>();
            this.alertConfigs = new HashMap<>();
        }

        public String getId() { return id; }

        // ─── Core Stock Operations ───

        @Override
        public void addStock(String productId, int quantity) {
            List<AlertConfig> alertsToFire = null;

            synchronized (this) {
                if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
                int currentQty = inventory.getOrDefault(productId, 0);
                int newQty = currentQty + quantity;
                inventory.put(productId, newQty);
                alertsToFire = getAlertsToFire(productId, currentQty, newQty);
            }

            // Fire alerts outside lock
            if (alertsToFire != null) {
                int qty = inventory.getOrDefault(productId, 0);
                for (AlertConfig alert : alertsToFire) {
                    alert.getListener().onLowStock(id, productId, qty);
                }
            }
        }

        @Override
        public boolean removeStock(String productId, int quantity) {
            List<AlertConfig> alertsToFire = null;
            int newQty;

            synchronized (this) {
                if (quantity <= 0) return false;
                int currentQty = inventory.getOrDefault(productId, 0);
                if (currentQty < quantity) return false;

                newQty = currentQty - quantity;
                inventory.put(productId, newQty);
                alertsToFire = getAlertsToFire(productId, currentQty, newQty);
            }

            // Fire alerts outside lock
            if (alertsToFire != null) {
                for (AlertConfig alert : alertsToFire) {
                    alert.getListener().onLowStock(id, productId, newQty);
                }
            }
            return true;
        }

        @Override
        public int getStock(String productId) {
            return inventory.getOrDefault(productId, 0);
        }

        /**
         * Available = physical stock - reserved stock.
         * Reserved inventory is invisible to other customers.
         */
        @Override
        public synchronized boolean checkAvailability(String productId, int quantity) {
            if (quantity <= 0) return false;
            int totalQty = inventory.getOrDefault(productId, 0);
            int reservedQty = reserved.getOrDefault(productId, 0);
            int availableQty = totalQty - reservedQty;
            return availableQty >= quantity;
        }

        // ─── Reservation System (Prevent Overselling) ───

        /**
         * Reserve stock for a customer starting checkout.
         * Reserved inventory can't be allocated to other orders.
         *
         * Flow:
         *   Customer clicks "Buy" → reserveStock (holds inventory)
         *   Customer completes payment → confirmReservation (deducts inventory)
         *   Customer abandons / timeout → releaseReservation (frees inventory)
         *
         * @param timeoutMs how long to hold the reservation (5-15 min typical)
         */
        public synchronized boolean reserveStock(String productId, int quantity,
                                                  String reservationId, long timeoutMs) {
            int totalQty = inventory.getOrDefault(productId, 0);
            int reservedQty = reserved.getOrDefault(productId, 0);
            int availableQty = totalQty - reservedQty;

            if (availableQty < quantity) return false;

            // Create reservation
            long expiresAt = System.currentTimeMillis() + timeoutMs;
            Reservation reservation = new Reservation(reservationId, productId, quantity, expiresAt);
            reservations.put(reservationId, reservation);

            // Update reserved count
            reserved.put(productId, reservedQty + quantity);
            return true;
        }

        /**
         * Confirm reservation after successful payment.
         * Actually deducts from physical inventory and releases the reservation hold.
         */
        public synchronized boolean confirmReservation(String reservationId) {
            Reservation reservation = reservations.get(reservationId);
            if (reservation == null) return false;

            if (reservation.isExpired()) return false; // Too late, reservation expired

            String productId = reservation.getProductId();
            int quantity = reservation.getQuantity();

            // Deduct from physical inventory
            int currentQty = inventory.getOrDefault(productId, 0);
            inventory.put(productId, currentQty - quantity);

            // Release reserved count
            int reservedQty = reserved.getOrDefault(productId, 0);
            reserved.put(productId, reservedQty - quantity);

            // Remove reservation record
            reservations.remove(reservationId);
            return true;
        }

        /**
         * Release reservation (customer abandoned cart or timeout).
         * Inventory becomes available to other customers again.
         */
        public synchronized void releaseReservation(String reservationId) {
            Reservation reservation = reservations.get(reservationId);
            if (reservation == null) return;

            String productId = reservation.getProductId();
            int reservedQty = reserved.getOrDefault(productId, 0);
            reserved.put(productId, reservedQty - reservation.getQuantity());

            reservations.remove(reservationId);
        }

        /**
         * Cleanup expired reservations — called by background task.
         * Releases inventory held by abandoned checkouts.
         */
        public synchronized void cleanupExpiredReservations() {
            long now = System.currentTimeMillis();
            List<String> expired = new ArrayList<>();

            for (Map.Entry<String, Reservation> entry : reservations.entrySet()) {
                if (now > entry.getValue().getExpiresAt()) {
                    expired.add(entry.getKey());
                }
            }

            for (String resId : expired) {
                releaseReservation(resId);
            }
        }

        // ─── Alert System ───

        public synchronized void setLowStockAlert(String productId, int threshold, AlertListener listener) {
            if (threshold <= 0) throw new IllegalArgumentException("Threshold must be positive");
            if (listener == null) throw new IllegalArgumentException("Listener cannot be null");

            List<AlertConfig> configs = alertConfigs.get(productId);
            if (configs == null) {
                configs = new ArrayList<>();
                alertConfigs.put(productId, configs);
            }
            configs.add(new AlertConfig(threshold, listener));
        }

        /**
         * Boundary crossing check — self-resetting, no state flag needed.
         * Only fires when stock drops FROM above threshold TO below it.
         */
        private List<AlertConfig> getAlertsToFire(String productId, int previousQty, int newQty) {
            List<AlertConfig> configs = alertConfigs.get(productId);
            if (configs == null) return null;

            List<AlertConfig> toFire = null;
            for (AlertConfig config : configs) {
                if (previousQty >= config.getThreshold() && newQty < config.getThreshold()) {
                    if (toFire == null) toFire = new ArrayList<>();
                    toFire.add(config);
                }
            }
            return toFire;
        }
    }

    // ═══════════════════════════════════════════════
    // Transfer (implements InventoryHolder)
    // ═══════════════════════════════════════════════
    //
    // Treats in-transit inventory as a first-class entity.
    // Stock removed from source warehouse lives HERE during shipment.
    // Total system inventory = sum(warehouses) + sum(transfers)
    //
    // Why: Real transfers take days (truck from CA to NY).
    //   Stock isn't at source OR destination during transit.
    //   Transfer object tracks where it is.
    // ═══════════════════════════════════════════════
    static class Transfer implements InventoryHolder {
        private final String transferId;
        private final String productId;
        private int quantity;
        private final String fromWarehouseId;
        private final String toWarehouseId;
        private final long createdAt;

        public Transfer(String transferId, String productId, int quantity,
                        String fromWarehouseId, String toWarehouseId) {
            this.transferId = transferId;
            this.productId = productId;
            this.quantity = quantity;
            this.fromWarehouseId = fromWarehouseId;
            this.toWarehouseId = toWarehouseId;
            this.createdAt = System.currentTimeMillis();
        }

        public String getTransferId() { return transferId; }
        public String getProductId() { return productId; }
        public int getQuantity() { return quantity; }
        public String getFromWarehouseId() { return fromWarehouseId; }
        public String getToWarehouseId() { return toWarehouseId; }

        @Override
        public void addStock(String productId, int qty) {
            if (productId.equals(this.productId)) this.quantity += qty;
        }

        @Override
        public boolean removeStock(String productId, int qty) {
            if (!productId.equals(this.productId) || this.quantity < qty) return false;
            this.quantity -= qty;
            return true;
        }

        @Override
        public int getStock(String productId) {
            return productId.equals(this.productId) ? this.quantity : 0;
        }

        @Override
        public boolean checkAvailability(String productId, int qty) {
            return productId.equals(this.productId) && this.quantity >= qty;
        }
    }

    // ═══════════════════════════════════════════════
    // Inventory Manager (Facade)
    // ═══════════════════════════════════════════════
    static class InventoryManager {
        private final Map<String, Warehouse> warehouses;
        private final Map<String, Transfer> activeTransfers;
        private int transferIdCounter = 0;

        public InventoryManager(List<String> warehouseIds) {
            this.warehouses = new LinkedHashMap<>();
            this.activeTransfers = new HashMap<>();
            for (String id : warehouseIds) {
                warehouses.put(id, new Warehouse(id));
            }
        }

        // ─── Core Operations ───

        public void addStock(String warehouseId, String productId, int quantity) {
            getWarehouse(warehouseId).addStock(productId, quantity);
        }

        public boolean removeStock(String warehouseId, String productId, int quantity) {
            return getWarehouse(warehouseId).removeStock(productId, quantity);
        }

        public List<String> getWarehousesWithAvailability(String productId, int quantity) {
            List<String> result = new ArrayList<>();
            for (Warehouse wh : warehouses.values()) {
                if (wh.checkAvailability(productId, quantity)) {
                    result.add(wh.getId());
                }
            }
            return result;
        }

        public void setLowStockAlert(String warehouseId, String productId,
                                      int threshold, AlertListener listener) {
            getWarehouse(warehouseId).setLowStockAlert(productId, threshold, listener);
        }

        // ─── Instant Transfer (atomic, deadlock-safe) ───

        /**
         * Instant transfer — atomic with lock ordering to prevent deadlock.
         * Use for logical transfers or when shipment time is negligible.
         */
        public boolean transfer(String productId, String fromWarehouseId, String toWarehouseId, int quantity) {
            if (quantity <= 0) return false;

            Warehouse from = warehouses.get(fromWarehouseId);
            Warehouse to = warehouses.get(toWarehouseId);
            if (from == null || to == null) return false;

            // Lock in consistent order to prevent deadlock
            Warehouse firstLock = fromWarehouseId.compareTo(toWarehouseId) < 0 ? from : to;
            Warehouse secondLock = fromWarehouseId.compareTo(toWarehouseId) < 0 ? to : from;

            synchronized (firstLock) {
                synchronized (secondLock) {
                    if (!from.removeStock(productId, quantity)) return false;
                    to.addStock(productId, quantity);
                    return true;
                }
            }
        }

        // ─── In-Transit Transfer (realistic, multi-day shipment) ───

        /**
         * Initiate a transfer — removes from source, creates Transfer object.
         * Stock lives in the Transfer until shipment arrives.
         *
         * Total system inventory stays constant:
         *   sum(warehouses) + sum(transfers) = unchanged
         */
        public String initiateTransfer(String productId, String fromWarehouseId,
                                        String toWarehouseId, int quantity) {
            Warehouse from = warehouses.get(fromWarehouseId);
            if (from == null) return null;

            if (!from.removeStock(productId, quantity)) return null;

            String transferId = "TXF-" + (++transferIdCounter);
            Transfer transfer = new Transfer(transferId, productId, quantity, fromWarehouseId, toWarehouseId);
            activeTransfers.put(transferId, transfer);
            return transferId;
        }

        /**
         * Complete transfer — shipment arrived, add stock to destination.
         * Called when external tracking system confirms delivery.
         */
        public boolean completeTransfer(String transferId) {
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer == null) return false;

            Warehouse to = warehouses.get(transfer.getToWarehouseId());
            if (to == null) return false;

            to.addStock(transfer.getProductId(), transfer.getQuantity());
            activeTransfers.remove(transferId);
            return true;
        }

        /**
         * Cancel transfer — return stock to source warehouse.
         */
        public boolean cancelTransfer(String transferId) {
            Transfer transfer = activeTransfers.get(transferId);
            if (transfer == null) return false;

            Warehouse from = warehouses.get(transfer.getFromWarehouseId());
            if (from == null) return false;

            from.addStock(transfer.getProductId(), transfer.getQuantity());
            activeTransfers.remove(transferId);
            return true;
        }

        /**
         * Get total system inventory for a product (warehouses + in-transit).
         */
        public int getTotalSystemStock(String productId) {
            int total = 0;
            for (Warehouse wh : warehouses.values()) {
                total += wh.getStock(productId);
            }
            for (Transfer t : activeTransfers.values()) {
                total += t.getStock(productId);
            }
            return total;
        }

        // ─── Reservation Operations (delegate to warehouse) ───

        public boolean reserveStock(String warehouseId, String productId,
                                     int quantity, String reservationId, long timeoutMs) {
            return getWarehouse(warehouseId).reserveStock(productId, quantity, reservationId, timeoutMs);
        }

        public boolean confirmReservation(String warehouseId, String reservationId) {
            return getWarehouse(warehouseId).confirmReservation(reservationId);
        }

        public void releaseReservation(String warehouseId, String reservationId) {
            getWarehouse(warehouseId).releaseReservation(reservationId);
        }

        // Helper
        private Warehouse getWarehouse(String warehouseId) {
            Warehouse wh = warehouses.get(warehouseId);
            if (wh == null) throw new IllegalArgumentException("Warehouse not found: " + warehouseId);
            return wh;
        }
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════
    public static void main(String[] args) {
        InventoryManager mgr = new InventoryManager(Arrays.asList("WH1", "WH2", "WH3"));

        mgr.addStock("WH1", "iPhone", 100);
        mgr.addStock("WH2", "iPhone", 50);
        mgr.addStock("WH3", "iPhone", 10);

        System.out.println("═══ Inventory Management System ═══\n");

        // ─── Low Stock Alerts ───
        mgr.setLowStockAlert("WH3", "iPhone", 5,
            (wh, prod, qty) -> System.out.println("⚠ ALERT: " + prod + " at " + wh + " low! Qty=" + qty));

        System.out.println("Warehouses with 10+ iPhones: " +
            mgr.getWarehousesWithAvailability("iPhone", 10));

        System.out.println("\nRemoving 8 iPhones from WH3...");
        System.out.println("Success: " + mgr.removeStock("WH3", "iPhone", 8));

        // ─── Reservation Demo ───
        System.out.println("\n═══ Reservation System ═══\n");
        System.out.println("WH2 has " + mgr.getWarehousesWithAvailability("iPhone", 1).size() + " warehouses with stock");

        // Customer A starts checkout — reserves 5 iPhones
        boolean resA = mgr.reserveStock("WH2", "iPhone", 5, "RES-A", 300000); // 5 min timeout
        System.out.println("Customer A reserves 5: " + resA);

        // Customer B tries to reserve 48 (only 45 available after A's reservation)
        boolean resB = mgr.reserveStock("WH2", "iPhone", 48, "RES-B", 300000);
        System.out.println("Customer B reserves 48: " + resB); // false! Only 45 available

        // Customer B tries 45 instead
        boolean resB2 = mgr.reserveStock("WH2", "iPhone", 45, "RES-B2", 300000);
        System.out.println("Customer B reserves 45: " + resB2); // true

        // Customer A completes payment
        boolean confirmed = mgr.confirmReservation("WH2", "RES-A");
        System.out.println("Customer A confirms: " + confirmed);
        System.out.println("WH2 stock after confirm: " + 45); // 50-5=45

        // Customer B abandons cart
        mgr.releaseReservation("WH2", "RES-B2");
        System.out.println("Customer B released reservation — stock available again");

        // ─── In-Transit Transfer Demo ───
        System.out.println("\n═══ In-Transit Transfer ═══\n");
        System.out.println("Total system iPhones before: " + mgr.getTotalSystemStock("iPhone"));

        // Ship 30 iPhones from WH1 to WH3 (takes 3 days in reality)
        String txfId = mgr.initiateTransfer("iPhone", "WH1", "WH3", 30);
        System.out.println("Initiated transfer: " + txfId);
        System.out.println("WH1 stock: " + mgr.getWarehousesWithAvailability("iPhone", 70));
        System.out.println("Total system (warehouses + transit): " + mgr.getTotalSystemStock("iPhone"));

        // 3 days later... shipment arrives
        boolean completed = mgr.completeTransfer(txfId);
        System.out.println("Transfer completed: " + completed);
        System.out.println("WH3 stock now: available for 30+? " +
            mgr.getWarehousesWithAvailability("iPhone", 30));
        System.out.println("Total system after: " + mgr.getTotalSystemStock("iPhone"));
    }
}
