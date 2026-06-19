import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Food Delivery System — Zomato/Swiggy/UberEats (SDE3 Interview, Minimal + Concurrency)
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * CORE ENTITIES
 * ═══════════════════════════════════════════════════════════════════════════════
 *   Restaurant, Customer, DeliveryPartner, Order, MenuItem
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * CORE FLOWS
 * ═══════════════════════════════════════════════════════════════════════════════
 *   Register → Browse/Search → Place Order → Assign Partner → Pickup → Deliver
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * CONCURRENCY MODEL
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   Data Structures:
 *     - ConcurrentHashMap for all entity maps (lock-free concurrent reads)
 *     - AtomicInteger for order ID generation (lock-free increment)
 *
 *   Locking:
 *     - ReentrantReadWriteLock per Restaurant:
 *         • Read lock  → searchRestaurants (browse menu, check availability)
 *                        Multiple customers can search concurrently without blocking
 *         • Write lock → placeOrder (deduct capacity + item stock)
 *                        releaseCapacity (increment capacity on delivery/cancel)
 *                        Exclusive — blocks both readers and other writers
 *     - ReentrantLock per Order (status transitions)
 *     - ReentrantLock per DeliveryPartner (status + assignment)
 *     - Global ReentrantLock for partner assignment (shared pool selection)
 *
 *   Lock Ordering (prevents deadlock):
 *     Restaurant.rwLock → Order.lock → DeliveryPartner.lock → partnerAssignmentLock
 *
 *   Why ReadWriteLock on Restaurant?
 *     - Read-heavy workload: 100x more searches than orders
 *     - Reads don't mutate state → safe to run concurrently
 *     - Writes (placeOrder) are the only mutation → need exclusive access
 *     - ReentrantLock would serialize searches behind orders (unnecessary)
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * DB ISOLATION LEVELS — FULL DISCUSSION
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   ┌─────────────────────┬────────────┬─────────────────────┬──────────────┬─────────────┐
 *   │ Level               │ Dirty Read │ Non-Repeatable Read │ Phantom Read │ Write Skew  │
 *   ├─────────────────────┼────────────┼─────────────────────┼──────────────┼─────────────┤
 *   │ READ UNCOMMITTED    │ Possible   │ Possible            │ Possible     │ Possible    │
 *   │ READ COMMITTED      │ No         │ Possible            │ Possible     │ Possible    │
 *   │ REPEATABLE READ     │ No         │ No                  │ Possible*    │ Possible    │
 *   │ SERIALIZABLE        │ No         │ No                  │ No           │ No          │
 *   └─────────────────────┴────────────┴─────────────────────┴──────────────┴─────────────┘
 *   * MySQL InnoDB prevents phantoms at REPEATABLE READ via gap locks
 *
 *   ─── READ UNCOMMITTED ────────────────────────────────────────────────────────
 *   Sees uncommitted data from other transactions (dirty reads).
 *   Example:
 *     TxA: UPDATE balance = 200 WHERE user='Alice' (NOT COMMITTED)
 *     TxB: SELECT balance → sees $200 (dirty!)
 *     TxA: ROLLBACK → Alice still has $1000, but TxB already acted on $200
 *   Use case: Almost never. Maybe approximate real-time dashboards.
 *
 *   ─── READ COMMITTED (chosen for this system) ────────────────────────────────
 *   Only sees committed data. Same query in same tx can return different results.
 *   Example:
 *     TxA: SELECT balance → $1000
 *     TxB: UPDATE balance = $500; COMMIT;
 *     TxA: SELECT balance → $500 (non-repeatable read!)
 *   Use case: General OLTP. PostgreSQL default. Good for stateless web requests.
 *   Why it works for food delivery:
 *     - Browsing is stateless (stale data acceptable, resolved at checkout)
 *     - Critical mutations use atomic conditional UPDATEs (row-level safety):
 *         • Place order:    UPDATE restaurants SET capacity = capacity - 1
 *                           WHERE id = ? AND capacity > 0
 *         • Payment:        UPDATE wallets SET balance = balance - ?
 *                           WHERE user_id = ? AND balance >= ?
 *         • Partner assign: UPDATE partners SET status = 'BUSY'
 *                           WHERE id = ? AND status = 'AVAILABLE'
 *     - No multi-row invariants needed for hot-path operations
 *
 *   ─── REPEATABLE READ ─────────────────────────────────────────────────────────
 *   Snapshot at transaction start. Same query always returns same result.
 *   Example:
 *     TxA: SELECT SUM(balance) → $1500
 *     TxB: INSERT ('Charlie', $300); COMMIT;
 *     TxA: SELECT SUM(balance) → still $1500 (snapshot isolation)
 *   MySQL default. Phantom rows possible in standard SQL (not in InnoDB).
 *   Use case: Reports reading same data multiple times (totals must match line items).
 *   Why NOT default for food delivery:
 *     - Holding snapshots longer → more contention at scale
 *     - No benefit for single-statement operations (which is what we do)
 *
 *   ─── SERIALIZABLE ────────────────────────────────────────────────────────────
 *   Transactions behave as if executed one-after-another. Prevents all anomalies.
 *   Example (write skew — prevented only at SERIALIZABLE):
 *     Rule: "At least 1 doctor must be on-call"
 *     TxA: SELECT COUNT(*) WHERE on_call=true → 2; UPDATE SET on_call=false WHERE name='Alice'
 *     TxB: SELECT COUNT(*) WHERE on_call=true → 2; UPDATE SET on_call=false WHERE name='Bob'
 *     Result without SERIALIZABLE: 0 doctors on-call! (invariant violated)
 *     At SERIALIZABLE: one tx aborted with serialization error
 *   Use case: Financial transfers, invariants across multiple rows.
 *   Why NOT default for food delivery:
 *     - Millions of orders/day → serialization failures → retry storms
 *     - Our invariants are single-row (capacity > 0, balance >= amount)
 *     - Overkill when atomic conditional UPDATE already guarantees correctness
 *
 *   ─── DECISION FOR THIS SYSTEM ───────────────────────────────────────────────
 *   Default: READ COMMITTED
 *   + Application-level correctness via:
 *     • Atomic conditional UPDATEs (WHERE condition prevents race)
 *     • SELECT ... FOR UPDATE (pessimistic row lock for partner assignment)
 *     • Optimistic locking with version column (refunds, status transitions)
 *     • Redis SETNX / distributed locks (cross-service partner assignment)
 *
 *   Escalate per-transaction when needed:
 *     │ Flow                        │ Isolation / Technique           │
 *     │ Financial reconciliation    │ SERIALIZABLE                    │
 *     │ Coupon claiming (limited)   │ READ COMMITTED + FOR UPDATE     │
 *     │ Refund processing           │ Optimistic lock (version col)   │
 *     │ Inventory sync (batch)      │ REPEATABLE READ                 │
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * DESIGN PATTERNS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   Strategy Pattern:
 *     - RestaurantSelectionStrategy: NearestAvailable, Cheapest
 *       (ranks restaurants for auto-selection in placeOrder without restaurantId)
 *     - PartnerAssignmentStrategy: NearestAvailable
 *       (selects delivery partner by proximity to restaurant)
 *
 *   State Machine:
 *     - Order lifecycle with validated transitions:
 *       PLACED → ACCEPTED → PREPARING → PICKED_UP → DELIVERED
 *                 ↓                                    (terminal)
 *               CANCELLED ←───────────────────────────
 *       Invalid transitions return error (e.g., can't cancel after PICKED_UP)
 *
 *   Overloaded API:
 *     - placeOrder(customerId, restaurantId, items)  → customer picks restaurant
 *     - placeOrder(customerId, items)                → system auto-selects via strategy
 *       (tries candidates in ranked order; falls through on RESTAURANT_FULL)
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * CAPACITY MODEL
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   Restaurant has maxCapacity (max concurrent orders it can handle).
 *   Lock is taken on remainingCapacity — models real-world kitchen bottleneck.
 *
 *   Why lock on capacity (not per-item)?
 *     - Simpler: one lock, one counter per restaurant
 *     - Models reality: kitchen throughput is the bottleneck, not individual items
 *     - Trade-off: serializes all orders to same restaurant (acceptable at restaurant scale)
 *     - Alternative (per-item lock) gives more concurrency but more complexity
 *
 *   Capacity lifecycle:
 *     placeOrder    → remainingCapacity-- (under write lock)
 *     delivery/cancel → remainingCapacity++ (under write lock)
 */
public class FoodDeliverySystem {

    // ═══════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════

    enum OrderStatus {
        PLACED, ACCEPTED, PREPARING, PICKED_UP, DELIVERED, CANCELLED
    }

    enum PartnerStatus { AVAILABLE, BUSY }

    // Valid transitions
    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = new HashMap<>();
    static {
        VALID_TRANSITIONS.put(OrderStatus.PLACED, new HashSet<>(Arrays.asList(OrderStatus.ACCEPTED, OrderStatus.CANCELLED)));
        VALID_TRANSITIONS.put(OrderStatus.ACCEPTED, new HashSet<>(Arrays.asList(OrderStatus.PREPARING, OrderStatus.CANCELLED)));
        VALID_TRANSITIONS.put(OrderStatus.PREPARING, new HashSet<>(Arrays.asList(OrderStatus.PICKED_UP)));
        VALID_TRANSITIONS.put(OrderStatus.PICKED_UP, new HashSet<>(Arrays.asList(OrderStatus.DELIVERED)));
    }

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class MenuItem {
        final String itemId;
        final String name;
        volatile long price; // can be updated
        volatile int quantity; // depletable stock

        MenuItem(String itemId, String name, long price, int quantity) {
            this.itemId = itemId;
            this.name = name;
            this.price = price;
            this.quantity = quantity;
        }

        boolean isAvailable() { return quantity > 0; }
    }

    static class Restaurant {
        final String id;
        final String name;
        final String pincode;
        final Map<String, MenuItem> menu = new ConcurrentHashMap<>();
        final int maxCapacity;              // max concurrent orders restaurant can handle
        volatile int remainingCapacity;     // current available slots
        final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

        Restaurant(String id, String name, String pincode, int maxCapacity) {
            this.id = id;
            this.name = name;
            this.pincode = pincode;
            this.maxCapacity = maxCapacity;
            this.remainingCapacity = maxCapacity;
        }

        void addMenuItem(String itemId, String name, long price, int quantity) {
            menu.put(itemId, new MenuItem(itemId, name, price, quantity));
        }

        /** Read lock — multiple threads can check availability concurrently */
        boolean hasAllItems(List<String> itemIds) {
            for (String id : itemIds) {
                MenuItem item = menu.get(id);
                if (item == null || !item.isAvailable()) return false;
            }
            return true;
        }

        long calculateTotal(List<String> itemIds) {
            long total = 0;
            for (String id : itemIds) {
                total += menu.get(id).price;
            }
            return total;
        }

        void releaseCapacity() {
            rwLock.writeLock().lock();
            try {
                if (remainingCapacity < maxCapacity) {
                    remainingCapacity++;
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }

    static class Customer {
        final String id;
        final String name;
        final String pincode;

        Customer(String id, String name, String pincode) {
            this.id = id;
            this.name = name;
            this.pincode = pincode;
        }
    }

    static class DeliveryPartner {
        final String id;
        final String name;
        volatile String currentPincode;
        volatile PartnerStatus status;
        volatile String currentOrderId;
        final ReentrantLock lock = new ReentrantLock();

        DeliveryPartner(String id, String name, String pincode) {
            this.id = id;
            this.name = name;
            this.currentPincode = pincode;
            this.status = PartnerStatus.AVAILABLE;
            this.currentOrderId = null;
        }
    }

    static class Order {
        final String orderId;
        final String customerId;
        final String restaurantId;
        final List<String> itemIds;
        final long totalAmount;
        volatile OrderStatus status;
        volatile String assignedPartnerId;
        final long createdAt;
        final ReentrantLock lock = new ReentrantLock();

        Order(String orderId, String customerId, String restaurantId,
              List<String> itemIds, long totalAmount) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.restaurantId = restaurantId;
            this.itemIds = new ArrayList<>(itemIds);
            this.totalAmount = totalAmount;
            this.status = OrderStatus.PLACED;
            this.assignedPartnerId = null;
            this.createdAt = System.currentTimeMillis();
        }
    }

    // ═══════════════════════════════════════════════
    // Strategy: Restaurant Selection
    // ═══════════════════════════════════════════════

    interface RestaurantSelectionStrategy {
        List<Restaurant> select(Collection<Restaurant> restaurants, String customerPincode, List<String> itemIds);
    }

    /** Select restaurants that have all requested items, prioritize same pincode, then by available capacity */
    static class NearestAvailableRestaurantStrategy implements RestaurantSelectionStrategy {
        @Override
        public List<Restaurant> select(Collection<Restaurant> restaurants, String customerPincode, List<String> itemIds) {
            List<Restaurant> samePincode = new ArrayList<>();
            List<Restaurant> otherPincode = new ArrayList<>();

            for (Restaurant r : restaurants) {
                if (r.remainingCapacity <= 0) continue;     // skip full restaurants
                if (!r.hasAllItems(itemIds)) continue;      // skip if items unavailable

                if (r.pincode.equals(customerPincode)) {
                    samePincode.add(r);
                } else {
                    otherPincode.add(r);
                }
            }

            // Sort each group by remaining capacity descending (prefer less loaded)
            Comparator<Restaurant> byCapacity = (a, b) -> Integer.compare(b.remainingCapacity, a.remainingCapacity);
            samePincode.sort(byCapacity);
            otherPincode.sort(byCapacity);

            List<Restaurant> result = new ArrayList<>(samePincode);
            result.addAll(otherPincode);
            return result;
        }
    }

    /** Select cheapest restaurant for the requested items */
    static class CheapestRestaurantStrategy implements RestaurantSelectionStrategy {
        @Override
        public List<Restaurant> select(Collection<Restaurant> restaurants, String customerPincode, List<String> itemIds) {
            List<Restaurant> eligible = new ArrayList<>();

            for (Restaurant r : restaurants) {
                if (r.remainingCapacity <= 0) continue;
                if (!r.hasAllItems(itemIds)) continue;
                eligible.add(r);
            }

            // Sort by total price ascending
            eligible.sort((a, b) -> Long.compare(a.calculateTotal(itemIds), b.calculateTotal(itemIds)));
            return eligible;
        }
    }

    // ═══════════════════════════════════════════════
    // Strategy: Partner Assignment
    // ═══════════════════════════════════════════════

    interface PartnerAssignmentStrategy {
        DeliveryPartner assign(Collection<DeliveryPartner> partners, String restaurantPincode);
    }

    /** Assign nearest available partner (same pincode first, then any available) */
    static class NearestAvailableStrategy implements PartnerAssignmentStrategy {
        @Override
        public DeliveryPartner assign(Collection<DeliveryPartner> partners, String restaurantPincode) {
            DeliveryPartner nearest = null;
            DeliveryPartner anyAvailable = null;

            for (DeliveryPartner p : partners) {
                if (p.status != PartnerStatus.AVAILABLE) continue;
                if (anyAvailable == null) anyAvailable = p;
                if (p.currentPincode.equals(restaurantPincode)) {
                    if (nearest == null || p.id.compareTo(nearest.id) < 0) {
                        nearest = p;
                    }
                }
            }
            return nearest != null ? nearest : anyAvailable;
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Map<String, Restaurant> restaurants = new ConcurrentHashMap<>();
    private final Map<String, Customer> customers = new ConcurrentHashMap<>();
    private final Map<String, DeliveryPartner> partners = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicInteger orderCounter = new AtomicInteger(0);
    private final PartnerAssignmentStrategy assignmentStrategy;
    private final RestaurantSelectionStrategy restaurantStrategy;
    private final ReentrantLock partnerAssignmentLock = new ReentrantLock(); // global for assignment

    public FoodDeliverySystem() {
        this.assignmentStrategy = new NearestAvailableStrategy();
        this.restaurantStrategy = new NearestAvailableRestaurantStrategy();
    }

    public FoodDeliverySystem(RestaurantSelectionStrategy restaurantStrategy) {
        this.assignmentStrategy = new NearestAvailableStrategy();
        this.restaurantStrategy = restaurantStrategy;
    }

    // ═══════════════════════════════════════════════
    // Registration
    // ═══════════════════════════════════════════════

    public void registerRestaurant(String id, String name, String pincode, int maxCapacity) {
        restaurants.putIfAbsent(id, new Restaurant(id, name, pincode, maxCapacity));
    }

    public void addMenuItem(String restaurantId, String itemId, String name, long price, int quantity) {
        Restaurant r = restaurants.get(restaurantId);
        if (r == null) throw new IllegalArgumentException("Restaurant not found");
        r.addMenuItem(itemId, name, price, quantity);
    }

    public void registerCustomer(String id, String name, String pincode) {
        customers.putIfAbsent(id, new Customer(id, name, pincode));
    }

    public void registerPartner(String id, String name, String pincode) {
        partners.putIfAbsent(id, new DeliveryPartner(id, name, pincode));
    }

    // ═══════════════════════════════════════════════
    // Place Order
    // ═══════════════════════════════════════════════

    /**
     * Place order without specifying a restaurant — system auto-selects using the
     * configured RestaurantSelectionStrategy (nearest, cheapest, etc.)
     *
     * Tries restaurants in strategy-ranked order. If first choice is full or items
     * become unavailable (race condition), falls through to next candidate.
     */
    public String placeOrder(String customerId, List<String> itemIds) {
        Customer customer = customers.get(customerId);
        if (customer == null) return "CUSTOMER_NOT_FOUND";

        // Strategy ranks eligible restaurants
        List<Restaurant> candidates = restaurantStrategy.select(restaurants.values(), customer.pincode, itemIds);
        if (candidates.isEmpty()) return "NO_RESTAURANT_AVAILABLE";

        // Try each candidate in ranked order (handles race: another thread took last slot)
        for (Restaurant restaurant : candidates) {
            String result = placeOrder(customerId, restaurant.id, itemIds);
            if (!result.equals("RESTAURANT_FULL") && !result.equals("ITEMS_UNAVAILABLE")) {
                return result; // success or unexpected error — return either way
            }
        }
        return "NO_RESTAURANT_AVAILABLE";
    }

    /**
     * Place order at a specific restaurant.
     * Thread-safe:
     *   1. Validate customer + restaurant
     *   2. Lock restaurant capacity → check capacity + item stock → deduct (all-or-nothing)
     *   3. Create order
     *   4. Auto-assign delivery partner (under global partner lock)
     *
     * Locking on restaurant capacity means all orders to the same restaurant
     * are serialized — simple, correct, models real-world kitchen bottleneck.
     *
     * Returns orderId or error message.
     */
    public String placeOrder(String customerId, String restaurantId, List<String> itemIds) {
        Customer customer = customers.get(customerId);
        if (customer == null) return "CUSTOMER_NOT_FOUND";

        Restaurant restaurant = restaurants.get(restaurantId);
        if (restaurant == null) return "RESTAURANT_NOT_FOUND";

        long total = 0;

        // Write lock — exclusive access for mutating capacity + stock
        restaurant.rwLock.writeLock().lock();
        try {
            // Check capacity
            if (restaurant.remainingCapacity <= 0) {
                return "RESTAURANT_FULL";
            }

            // Check all items available
            if (!restaurant.hasAllItems(itemIds)) {
                return "ITEMS_UNAVAILABLE";
            }

            total = restaurant.calculateTotal(itemIds);

            // All good — deduct capacity + item stock atomically
            restaurant.remainingCapacity--;
            for (String itemId : itemIds) {
                restaurant.menu.get(itemId).quantity--;
            }
        } finally {
            restaurant.rwLock.writeLock().unlock();
        }

        // Create order (AtomicInteger for ID, ConcurrentHashMap.put for storage)
        String orderId = "ORD-" + orderCounter.incrementAndGet();
        Order order = new Order(orderId, customerId, restaurantId, itemIds, total);
        orders.put(orderId, order);

        // Auto-assign partner (NEEDS lock — shared partner pool)
        assignPartner(order, restaurant.pincode);

        return orderId;
    }

    // ═══════════════════════════════════════════════
    // Partner Assignment (thread-safe)
    // ═══════════════════════════════════════════════

    private void assignPartner(Order order, String restaurantPincode) {
        partnerAssignmentLock.lock();
        try {
            DeliveryPartner selected = assignmentStrategy.assign(partners.values(), restaurantPincode);
            if (selected != null) {
                selected.lock.lock();
                try {
                    selected.status = PartnerStatus.BUSY;
                    selected.currentOrderId = order.orderId;
                } finally {
                    selected.lock.unlock();
                }
                order.assignedPartnerId = selected.id;
                order.status = OrderStatus.ACCEPTED;
            }
            // If no partner available, order stays PLACED (can be assigned later)
        } finally {
            partnerAssignmentLock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // Update Order Status (State Machine)
    // ═══════════════════════════════════════════════

    public String updateOrderStatus(String orderId, OrderStatus newStatus) {
        Order order = orders.get(orderId);
        if (order == null) return "ORDER_NOT_FOUND";

        order.lock.lock();
        try {
            Set<OrderStatus> allowed = VALID_TRANSITIONS.getOrDefault(order.status, Collections.emptySet());
            if (!allowed.contains(newStatus)) {
                return "INVALID_TRANSITION";
            }
            order.status = newStatus;

            // On delivery complete or cancel: free partner + release restaurant capacity
            if (newStatus == OrderStatus.DELIVERED || newStatus == OrderStatus.CANCELLED) {
                freePartner(order.assignedPartnerId);
                Restaurant restaurant = restaurants.get(order.restaurantId);
                if (restaurant != null) {
                    restaurant.releaseCapacity();
                }
            }
        } finally {
            order.lock.unlock();
        }
        return "OK";
    }

    // ═══════════════════════════════════════════════
    // Cancel Order
    // ═══════════════════════════════════════════════

    public String cancelOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) return "ORDER_NOT_FOUND";

        order.lock.lock();
        try {
            if (order.status == OrderStatus.PICKED_UP || order.status == OrderStatus.DELIVERED) {
                return "CANNOT_CANCEL";
            }
            order.status = OrderStatus.CANCELLED;

            // Release restaurant capacity
            Restaurant restaurant = restaurants.get(order.restaurantId);
            if (restaurant != null) {
                restaurant.releaseCapacity();
            }

            if (order.assignedPartnerId != null) {
                freePartner(order.assignedPartnerId);
            }
        } finally {
            order.lock.unlock();
        }
        return "CANCELLED";
    }

    // ═══════════════════════════════════════════════
    // Free Partner (after delivery/cancel)
    // ═══════════════════════════════════════════════

    private void freePartner(String partnerId) {
        if (partnerId == null) return;
        DeliveryPartner partner = partners.get(partnerId);
        if (partner == null) return;

        partner.lock.lock();
        try {
            partner.status = PartnerStatus.AVAILABLE;
            partner.currentOrderId = null;
        } finally {
            partner.lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // Queries
    // ═══════════════════════════════════════════════

    /** Search restaurants using the configured strategy (nearest, cheapest, etc.)
     *  Uses read locks — multiple customers can search concurrently without blocking each other.
     */
    public List<String> searchRestaurants(String customerId, List<String> itemIds) {
        Customer customer = customers.get(customerId);
        if (customer == null) return Collections.singletonList("CUSTOMER_NOT_FOUND");

        // Acquire read locks on all restaurants for consistent snapshot
        List<Restaurant> allRestaurants = new ArrayList<>(restaurants.values());
        for (Restaurant r : allRestaurants) {
            r.rwLock.readLock().lock();
        }
        try {
            List<Restaurant> selected = restaurantStrategy.select(allRestaurants, customer.pincode, itemIds);
            List<String> result = new ArrayList<>();
            for (Restaurant r : selected) {
                long total = r.calculateTotal(itemIds);
                result.add(r.id + "|" + r.name + "|" + r.pincode + "|capacity:" + r.remainingCapacity + "|$" + total);
            }
            return result;
        } finally {
            for (Restaurant r : allRestaurants) {
                r.rwLock.readLock().unlock();
            }
        }
    }

    public String getOrderStatus(String orderId) {
        Order order = orders.get(orderId);
        return order != null ? order.status.name() : "NOT_FOUND";
    }

    public String getPartnerStatus(String partnerId) {
        DeliveryPartner p = partners.get(partnerId);
        if (p == null) return "NOT_FOUND";
        return p.status == PartnerStatus.AVAILABLE ? "AVAILABLE" : "BUSY:" + p.currentOrderId;
    }

    public List<String> getOrdersForCustomer(String customerId) {
        List<String> result = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.customerId.equals(customerId)) {
                result.add(o.orderId + "|" + o.restaurantId + "|" + o.status + "|$" + o.totalAmount);
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) throws InterruptedException {
        FoodDeliverySystem system = new FoodDeliverySystem();

        // Setup
        system.registerRestaurant("R1", "Pizza Palace", "560001", 5);  // max 5 concurrent orders
        system.registerRestaurant("R2", "Burger Barn", "560001", 3);   // max 3 concurrent orders
        system.addMenuItem("R1", "pizza", "Margherita Pizza", 250, 10);
        system.addMenuItem("R1", "pasta", "Penne Alfredo", 200, 5);
        system.addMenuItem("R2", "burger", "Classic Burger", 150, 20);
        system.addMenuItem("R2", "fries", "French Fries", 80, 50);

        system.registerCustomer("C1", "Alice", "560001");
        system.registerCustomer("C2", "Bob", "560002");

        system.registerPartner("D1", "Raju", "560001");
        system.registerPartner("D2", "Suresh", "560002");

        System.out.println("═══ Food Delivery System ═══\n");

        // ─── Place orders ───
        System.out.println("--- Place Orders ---");
        String o1 = system.placeOrder("C1", "R1", Arrays.asList("pizza", "pasta"));
        System.out.println("Order 1: " + o1 + " status=" + system.getOrderStatus(o1));
        System.out.println("D1: " + system.getPartnerStatus("D1")); // BUSY:ORD-1

        String o2 = system.placeOrder("C2", "R2", Arrays.asList("burger", "fries"));
        System.out.println("Order 2: " + o2 + " status=" + system.getOrderStatus(o2));
        System.out.println("D2: " + system.getPartnerStatus("D2")); // BUSY:ORD-2

        // ─── Order lifecycle ───
        System.out.println("\n--- Order Lifecycle ---");
        System.out.println("Prepare O1: " + system.updateOrderStatus(o1, OrderStatus.PREPARING));
        System.out.println("Pickup O1: " + system.updateOrderStatus(o1, OrderStatus.PICKED_UP));
        System.out.println("Deliver O1: " + system.updateOrderStatus(o1, OrderStatus.DELIVERED));
        System.out.println("D1 after delivery: " + system.getPartnerStatus("D1")); // AVAILABLE

        // ─── Cancel ───
        System.out.println("\n--- Cancel ---");
        System.out.println("Cancel O2: " + system.cancelOrder(o2));
        System.out.println("D2 after cancel: " + system.getPartnerStatus("D2")); // AVAILABLE

        // ─── Invalid transitions ───
        System.out.println("\n--- Edge Cases ---");
        System.out.println("Deliver already delivered: " + system.updateOrderStatus(o1, OrderStatus.DELIVERED));
        System.out.println("Cancel delivered: " + system.cancelOrder(o1));

        // ─── Concurrent orders ───
        System.out.println("\n--- Concurrent Orders (3 orders, 2 partners) ---");
        Thread[] threads = new Thread[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                String oid = system.placeOrder("C1", "R1", Arrays.asList("pizza"));
                System.out.println("  Thread " + idx + ": " + oid + " → " + system.getOrderStatus(oid));
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("D1: " + system.getPartnerStatus("D1"));
        System.out.println("D2: " + system.getPartnerStatus("D2"));

        // ─── Restaurant selection ───
        System.out.println("\n--- Restaurant Selection (search for 'pizza') ---");
        system.searchRestaurants("C1", Arrays.asList("pizza")).forEach(s -> System.out.println("  " + s));

        // ─── Auto-select restaurant via strategy ───
        System.out.println("\n--- Place Order with Auto-Selection (no restaurantId) ---");
        String autoOrder = system.placeOrder("C2", Arrays.asList("pizza"));
        System.out.println("Auto-selected order: " + autoOrder + " status=" + system.getOrderStatus(autoOrder));

        // ─── Customer history ───
        System.out.println("\n--- Customer C1 Orders ---");
        system.getOrdersForCustomer("C1").forEach(s -> System.out.println("  " + s));
    }
}
