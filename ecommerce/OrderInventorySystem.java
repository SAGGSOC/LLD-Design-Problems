import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Order & Inventory Management System — Multi-Threaded (Interview Style)
 *
 * Features:
 *   - Create sellers with serviceable pincodes and payment modes
 *   - Add inventory (seller adds stock for a product)
 *   - Get inventory (current stock for product+seller)
 *   - Create order (validates pincode, payment, inventory; deducts stock atomically)
 *
 * Concurrency:
 *   - ConcurrentHashMap for sellers and orders
 *   - ReentrantLock per (productId, sellerId) for inventory mutations
 *     → Two threads ordering from different sellers don't block each other
 *     → Two threads ordering same product from same seller serialize correctly
 *
 * Key: Inventory is per (product, seller) — not global per product.
 */
public class OrderInventorySystem {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class Seller {
        final String sellerId;
        final Set<String> serviceablePincodes;
        final Set<String> paymentModes;

        Seller(String sellerId, List<String> serviceablePincodes, List<String> paymentModes) {
            this.sellerId = sellerId;
            this.serviceablePincodes = ConcurrentHashMap.newKeySet();
            this.serviceablePincodes.addAll(serviceablePincodes);
            this.paymentModes = ConcurrentHashMap.newKeySet();
            this.paymentModes.addAll(paymentModes);
        }

        boolean canServicePincode(String pincode) { return serviceablePincodes.contains(pincode); }
        boolean supportsPayment(String paymentMode) { return paymentModes.contains(paymentMode); }
    }

    static class Order {
        final String orderId;
        final String destinationPincode;
        final String sellerId;
        final int productId;
        final int productCount;
        final String paymentMode;

        Order(String orderId, String destinationPincode, String sellerId,
              int productId, int productCount, String paymentMode) {
            this.orderId = orderId;
            this.destinationPincode = destinationPincode;
            this.sellerId = sellerId;
            this.productId = productId;
            this.productCount = productCount;
            this.paymentMode = paymentMode;
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private int productsCount;
    private final Map<String, Seller> sellers = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    // Key: "productId|sellerId" → current inventory count
    private final Map<String, Integer> inventory = new ConcurrentHashMap<>();
    // Per (product, seller) lock for inventory mutations
    private final Map<String, ReentrantLock> inventoryLocks = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════
    // init
    // ═══════════════════════════════════════════════

    public void init(int productsCount) {
        this.productsCount = productsCount;
        System.out.println("Initialized with " + productsCount + " products");
    }

    // ═══════════════════════════════════════════════
    // createSeller
    // ═══════════════════════════════════════════════

    public void createSeller(String sellerId, List<String> serviceablePincodes, List<String> paymentModes) {
        sellers.put(sellerId, new Seller(sellerId, serviceablePincodes, paymentModes));
        System.out.println("Seller created: " + sellerId);
    }

    // ═══════════════════════════════════════════════
    // addInventory
    // ═══════════════════════════════════════════════

    public String addInventory(int productId, String sellerId, int delta) {
        String key = productId + "|" + sellerId;
        ReentrantLock lock = inventoryLocks.computeIfAbsent(key, k -> new ReentrantLock());

        lock.lock();
        try {
            int current = inventory.getOrDefault(key, 0);
            inventory.put(key, current + delta);
        } finally {
            lock.unlock();
        }
        return "inventory added";
    }

    // ═══════════════════════════════════════════════
    // getInventory
    // ═══════════════════════════════════════════════

    public int getInventory(int productId, String sellerId) {
        String key = productId + "|" + sellerId;
        return inventory.getOrDefault(key, 0);
    }

    // ═══════════════════════════════════════════════
    // createOrder
    //
    // Validation order:
    //   1. Pincode serviceable by seller?
    //   2. Payment mode supported by seller?
    //   3. Sufficient inventory? (checked under lock)
    //
    // Only deducts inventory if ALL validations pass.
    // ═══════════════════════════════════════════════

    public String createOrder(String orderId, String destinationPincode, String sellerId,
                               int productId, int productCount, String paymentMode) {

        Seller seller = sellers.get(sellerId);
        if (seller == null) return "invalid seller";

        // Validation 1: Pincode
        if (!seller.canServicePincode(destinationPincode)) {
            return "pincode unserviceable";
        }

        // Validation 2: Payment mode
        if (!seller.supportsPayment(paymentMode)) {
            return "payment mode not supported";
        }

        // Validation 3 + Deduction: Inventory (under lock for atomicity)
        String inventoryKey = productId + "|" + sellerId;
        ReentrantLock lock = inventoryLocks.computeIfAbsent(inventoryKey, k -> new ReentrantLock());

        lock.lock();
        try {
            int current = inventory.getOrDefault(inventoryKey, 0);
            if (current < productCount) {
                return "insufficient product inventory";
            }

            // Deduct inventory
            inventory.put(inventoryKey, current - productCount);

            // Create order record
            Order order = new Order(orderId, destinationPincode, sellerId, productId, productCount, paymentMode);
            orders.put(orderId, order);
        } finally {
            lock.unlock();
        }

        return "order placed";
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) throws InterruptedException {
        OrderInventorySystem system = new OrderInventorySystem();
        system.init(10);

        System.out.println("\n═══ Setup: Sellers & Inventory ═══\n");

        system.createSeller("seller-0",
            Arrays.asList("110001", "560092", "452001", "700001"),
            Arrays.asList("netbanking", "cash", "upi"));

        system.createSeller("seller-1",
            Arrays.asList("400050", "110001", "600032", "560092"),
            Arrays.asList("netbanking", "cash", "upi"));

        System.out.println("addInventory(0, seller-1, 52): " + system.addInventory(0, "seller-1", 52));
        System.out.println("addInventory(0, seller-0, 32): " + system.addInventory(0, "seller-0", 32));

        System.out.println("\n═══ Orders ═══\n");

        System.out.println("createOrder(order-1, 400050, seller-1, 0, 5, upi): " +
            system.createOrder("order-1", "400050", "seller-1", 0, 5, "upi"));
        // order placed

        System.out.println("getInventory(0, seller-1): " + system.getInventory(0, "seller-1"));
        // 47

        System.out.println("createOrder(order-2, 560092, seller-0, 0, 1, upi): " +
            system.createOrder("order-2", "560092", "seller-0", 0, 1, "upi"));
        // order placed

        System.out.println("getInventory(0, seller-0): " + system.getInventory(0, "seller-0"));
        // 31

        // ─── Edge cases ───
        System.out.println("\n═══ Edge Cases ═══\n");

        System.out.println("Unserviceable pincode: " +
            system.createOrder("order-3", "999999", "seller-0", 0, 1, "cash"));
        // pincode unserviceable

        System.out.println("Unsupported payment: " +
            system.createOrder("order-4", "110001", "seller-0", 0, 1, "credit card"));
        // payment mode not supported

        System.out.println("Insufficient inventory: " +
            system.createOrder("order-5", "110001", "seller-0", 0, 100, "cash"));
        // insufficient product inventory

        // ─── Concurrent orders ───
        System.out.println("\n═══ Concurrent Orders (same product, same seller) ═══\n");

        system.addInventory(1, "seller-0", 10);
        System.out.println("Inventory product 1, seller-0: " + system.getInventory(1, "seller-0"));

        // 5 threads each try to order 3 items (only 3 can succeed with 10 stock)
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                String result = system.createOrder("concurrent-" + idx, "110001", "seller-0", 1, 3, "cash");
                System.out.println("  Thread " + idx + ": " + result);
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("Final inventory product 1, seller-0: " + system.getInventory(1, "seller-0"));
        // Should be 10 - (3 * 3) = 1 (3 orders succeed, 2 fail with insufficient)
    }
}
