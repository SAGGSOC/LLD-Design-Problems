import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Peer-to-Peer Parcel Delivery System — Thread-Safe (Interview Style)
 *
 * Features:
 *   - Auto-assign orders to lexicographically lowest available driver
 *   - Pending queue when no drivers available (FIFO)
 *   - Cancel assigned-but-not-picked orders (frees driver)
 *   - Canceled orders skipped during pending queue processing
 *   - Delivery frees driver → auto-assigns next pending order
 *
 * Concurrency:
 *   - Global ReentrantLock for order/driver state mutations
 *     (assignment, cancellation, pickup, delivery all affect shared state)
 *   - ConcurrentHashMap for reads (getOrderStatus, getDriverStatus)
 *
 * State Machine:
 *   CREATED → ASSIGNED → PICKED_UP → DELIVERED
 *   CREATED/ASSIGNED → CANCELED
 */
public class PeerToPeerDeliverySystem {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    enum OrderStatus { CREATED, ASSIGNED, PICKED_UP, DELIVERED, CANCELED }
    enum DriverStatus { AVAILABLE, BUSY }

    static class Order {
        final String orderId;
        final String customerId;
        final String itemId;
        OrderStatus status;
        String assignedDriverId;

        Order(String orderId, String customerId, String itemId) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.itemId = itemId;
            this.status = OrderStatus.CREATED;
            this.assignedDriverId = null;
        }
    }

    static class Driver {
        final String driverId;
        DriverStatus status;
        String currentOrderId;

        Driver(String driverId) {
            this.driverId = driverId;
            this.status = DriverStatus.AVAILABLE;
            this.currentOrderId = null;
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Set<String> validCustomerIds;
    private final Set<String> validItemIds;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();
    // Sorted set of available driver IDs (lexicographic order)
    private final TreeSet<String> availableDrivers = new TreeSet<>();
    // FIFO queue of pending order IDs (waiting for driver)
    private final LinkedList<String> pendingQueue = new LinkedList<>();
    // Global lock for state mutations
    private final ReentrantLock lock = new ReentrantLock();

    // ═══════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════

    public PeerToPeerDeliverySystem(int totalUsers, int totalDrivers, List<String> deliverableItemIds) {
        validCustomerIds = new HashSet<>();
        for (int i = 0; i < totalUsers; i++) {
            validCustomerIds.add("user-" + i);
        }

        validItemIds = new HashSet<>(deliverableItemIds);

        for (int i = 0; i < totalDrivers; i++) {
            String driverId = "dv-" + i;
            drivers.put(driverId, new Driver(driverId));
            availableDrivers.add(driverId);
        }
    }

    // ═══════════════════════════════════════════════
    // 1. Create Order
    // ═══════════════════════════════════════════════

    public String createOrder(String orderId, String customerId, String itemId) {
        // Validate
        if (!validCustomerIds.contains(customerId)) return "INVALID";
        if (!validItemIds.contains(itemId)) return "INVALID";
        if (orders.containsKey(orderId)) return "INVALID";

        lock.lock();
        try {
            Order order = new Order(orderId, customerId, itemId);
            orders.put(orderId, order);

            // Try to assign immediately
            if (!availableDrivers.isEmpty()) {
                String driverId = availableDrivers.pollFirst(); // lexicographically lowest
                assignOrderToDriver(order, drivers.get(driverId));
                return "ACCEPTED";
            } else {
                // No driver available — queue it
                pendingQueue.add(orderId);
                return "PENDING";
            }
        } finally {
            lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // 2. Cancel Order
    // ═══════════════════════════════════════════════

    public String cancelOrder(String orderId) {
        lock.lock();
        try {
            Order order = orders.get(orderId);
            if (order == null) return "ERROR";

            if (order.status == OrderStatus.CREATED) {
                // Not yet assigned — just cancel and remove from pending queue
                order.status = OrderStatus.CANCELED;
                pendingQueue.remove(orderId);
                return "CANCELED";
            } else if (order.status == OrderStatus.ASSIGNED) {
                // Assigned but not picked up — cancel and free the driver
                order.status = OrderStatus.CANCELED;
                Driver driver = drivers.get(order.assignedDriverId);
                freeDriver(driver);
                return "CANCELED";
            } else {
                // PICKED_UP, DELIVERED, CANCELED — cannot cancel
                return "ERROR";
            }
        } finally {
            lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // 3. Pick Up Order
    // ═══════════════════════════════════════════════

    public String pickUpOrder(String driverId, String orderId) {
        lock.lock();
        try {
            if (!drivers.containsKey(driverId)) return "ERROR";

            Order order = orders.get(orderId);
            if (order == null) return "ERROR";
            if (order.status != OrderStatus.ASSIGNED) return "ERROR";
            if (!driverId.equals(order.assignedDriverId)) return "ERROR";

            order.status = OrderStatus.PICKED_UP;
            return "PICKED_UP";
        } finally {
            lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // 4. Deliver Order
    // ═══════════════════════════════════════════════

    public String deliverOrder(String driverId, String orderId) {
        lock.lock();
        try {
            if (!drivers.containsKey(driverId)) return "ERROR";

            Order order = orders.get(orderId);
            if (order == null) return "ERROR";
            if (order.status != OrderStatus.PICKED_UP) return "ERROR";
            if (!driverId.equals(order.assignedDriverId)) return "ERROR";

            order.status = OrderStatus.DELIVERED;
            Driver driver = drivers.get(driverId);
            freeDriver(driver);
            return "DELIVERED";
        } finally {
            lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // 5. Get Order Status
    // ═══════════════════════════════════════════════

    public String getOrderStatus(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) return "ERROR";
        return order.status.name();
    }

    // ═══════════════════════════════════════════════
    // 6. Get Driver Status
    // ═══════════════════════════════════════════════

    public String getDriverStatus(String driverId) {
        Driver driver = drivers.get(driverId);
        if (driver == null) return "ERROR";
        if (driver.status == DriverStatus.AVAILABLE) return "AVAILABLE";
        return "BUSY:" + driver.currentOrderId;
    }

    // ═══════════════════════════════════════════════
    // Internal: Assignment & Freeing
    // ═══════════════════════════════════════════════

    /** Assign an order to a driver. Must be called while holding lock. */
    private void assignOrderToDriver(Order order, Driver driver) {
        order.status = OrderStatus.ASSIGNED;
        order.assignedDriverId = driver.driverId;
        driver.status = DriverStatus.BUSY;
        driver.currentOrderId = order.orderId;
    }

    /**
     * Free a driver and assign next pending order (if any).
     * Skips canceled orders in the pending queue.
     * Must be called while holding lock.
     */
    private void freeDriver(Driver driver) {
        driver.status = DriverStatus.AVAILABLE;
        driver.currentOrderId = null;

        // Try to assign next pending order (skip canceled ones)
        while (!pendingQueue.isEmpty()) {
            String nextOrderId = pendingQueue.pollFirst();
            Order nextOrder = orders.get(nextOrderId);

            if (nextOrder != null && nextOrder.status == OrderStatus.CREATED) {
                // Found a valid pending order — assign it
                assignOrderToDriver(nextOrder, driver);
                return;
            }
            // Else: order was canceled while pending — skip it
        }

        // No pending orders — driver goes back to available pool
        availableDrivers.add(driver.driverId);
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        // ─── Example 1: Basic flow ───
        System.out.println("═══ Example 1: Basic Flow ═══\n");

        PeerToPeerDeliverySystem sys = new PeerToPeerDeliverySystem(3, 2, Arrays.asList("ITEM_1", "ITEM_2"));

        System.out.println("createOrder O1: " + sys.createOrder("O1", "user-0", "ITEM_1"));   // ACCEPTED
        System.out.println("getOrderStatus O1: " + sys.getOrderStatus("O1"));                  // ASSIGNED
        System.out.println("getDriverStatus dv-0: " + sys.getDriverStatus("dv-0"));            // BUSY:O1
        System.out.println("getDriverStatus dv-1: " + sys.getDriverStatus("dv-1"));            // AVAILABLE
        System.out.println("pickUpOrder dv-0 O1: " + sys.pickUpOrder("dv-0", "O1"));          // PICKED_UP
        System.out.println("cancelOrder O1: " + sys.cancelOrder("O1"));                        // ERROR
        System.out.println("deliverOrder dv-0 O1: " + sys.deliverOrder("dv-0", "O1"));        // DELIVERED
        System.out.println("getDriverStatus dv-0: " + sys.getDriverStatus("dv-0"));            // AVAILABLE

        // ─── Example 2: Cancel before pickup ───
        System.out.println("\n═══ Example 2: Cancel Before Pickup ═══\n");

        PeerToPeerDeliverySystem sys2 = new PeerToPeerDeliverySystem(2, 1, Arrays.asList("ITEM_A"));

        System.out.println("createOrder O1: " + sys2.createOrder("O1", "user-0", "ITEM_A"));   // ACCEPTED
        System.out.println("getDriverStatus dv-0: " + sys2.getDriverStatus("dv-0"));            // BUSY:O1
        System.out.println("cancelOrder O1: " + sys2.cancelOrder("O1"));                        // CANCELED
        System.out.println("getDriverStatus dv-0: " + sys2.getDriverStatus("dv-0"));            // AVAILABLE
        System.out.println("pickUpOrder dv-0 O1: " + sys2.pickUpOrder("dv-0", "O1"));          // ERROR
        System.out.println("createOrder BAD1 invalid item: " + sys2.createOrder("BAD1", "user-0", "UNKNOWN"));  // INVALID
        System.out.println("createOrder O2: " + sys2.createOrder("O2", "user-1", "ITEM_A"));   // ACCEPTED
        System.out.println("getOrderStatus O2: " + sys2.getOrderStatus("O2"));                  // ASSIGNED
        System.out.println("getDriverStatus dv-0: " + sys2.getDriverStatus("dv-0"));            // BUSY:O2

        // ─── Example 3: Pending queue + FIFO ───
        System.out.println("\n═══ Example 3: Pending Queue ═══\n");

        PeerToPeerDeliverySystem sys3 = new PeerToPeerDeliverySystem(3, 1, Arrays.asList("X", "Y"));

        System.out.println("createOrder O1: " + sys3.createOrder("O1", "user-0", "X"));        // ACCEPTED
        System.out.println("pickUpOrder dv-0 O1: " + sys3.pickUpOrder("dv-0", "O1"));          // PICKED_UP
        System.out.println("createOrder O2: " + sys3.createOrder("O2", "user-1", "Y"));        // PENDING
        System.out.println("createOrder O3: " + sys3.createOrder("O3", "user-2", "X"));        // PENDING
        System.out.println("getOrderStatus O2: " + sys3.getOrderStatus("O2"));                  // CREATED
        System.out.println("getOrderStatus O3: " + sys3.getOrderStatus("O3"));                  // CREATED
        System.out.println("cancelOrder O3: " + sys3.cancelOrder("O3"));                        // CANCELED
        System.out.println("deliverOrder dv-0 O1: " + sys3.deliverOrder("dv-0", "O1"));        // DELIVERED
        System.out.println("getOrderStatus O2: " + sys3.getOrderStatus("O2"));                  // ASSIGNED (O2 was first in queue)
        System.out.println("getDriverStatus dv-0: " + sys3.getDriverStatus("dv-0"));            // BUSY:O2
        System.out.println("pickUpOrder dv-0 O3: " + sys3.pickUpOrder("dv-0", "O3"));          // ERROR
    }
}
