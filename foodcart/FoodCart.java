import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * FoodCart — Simplified Food Order Management System (Interview Style)
 *
 * Features:
 *   - Add restaurants with capacity and menu
 *   - Update menu (upsert/remove items)
 *   - Place orders with restaurant selection strategy (Strategy Pattern)
 *   - Dispatch orders (frees capacity, tracks served items)
 *   - Query: items served per restaurant, dispatched orders
 *
 * Design:
 *   - Strategy Pattern: LOWEST_TOTAL_PRICE, MAX_REMAINING_CAPACITY
 *   - Commands sorted by timestamp before execution, outputs aligned to original positions
 */
public class FoodCart {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class Restaurant {
        final String id;
        final int capacity;
        Map<String, Long> menu; // itemName → price
        int openOrders;
        final ReentrantLock lock = new ReentrantLock(); // per-restaurant lock

        Restaurant(String id, int capacity, Map<String, Long> menu) {
            this.id = id;
            this.capacity = capacity;
            this.menu = new HashMap<>(menu);
            this.openOrders = 0;
        }

        int remainingCapacity() { return capacity - openOrders; }

        boolean hasAllItems(List<String> items) {
            for (String item : items) {
                if (!menu.containsKey(item)) return false;
            }
            return true;
        }

        long totalPrice(List<String> items) {
            long total = 0;
            for (String item : items) {
                total += menu.get(item);
            }
            return total;
        }

        boolean canAccept(List<String> items) {
            return hasAllItems(items) && remainingCapacity() > 0;
        }
    }

    static class Order {
        final String orderId;
        final String customerId;
        final String restaurantId;
        final List<String> items;
        final String placedTimestamp;
        String dispatchTimestamp;
        boolean dispatched;

        Order(String orderId, String customerId, String restaurantId, List<String> items, String placedTimestamp) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.restaurantId = restaurantId;
            this.items = items;
            this.placedTimestamp = placedTimestamp;
            this.dispatched = false;
        }
    }

    // ═══════════════════════════════════════════════
    // Strategy Pattern: Restaurant Selection
    // ═══════════════════════════════════════════════

    interface RestaurantSelectionStrategy {
        Restaurant select(List<Restaurant> candidates, List<String> items);
    }

    static class LowestTotalPriceStrategy implements RestaurantSelectionStrategy {
        @Override
        public Restaurant select(List<Restaurant> candidates, List<String> items) {
            Restaurant best = null;
            for (Restaurant r : candidates) {
                if (best == null) { best = r; continue; }
                long priceCurr = r.totalPrice(items);
                long priceBest = best.totalPrice(items);
                if (priceCurr < priceBest) { best = r; }
                else if (priceCurr == priceBest) {
                    if (r.remainingCapacity() > best.remainingCapacity()) { best = r; }
                    else if (r.remainingCapacity() == best.remainingCapacity()) {
                        if (r.id.compareTo(best.id) < 0) { best = r; }
                    }
                }
            }
            return best;
        }
    }

    static class MaxRemainingCapacityStrategy implements RestaurantSelectionStrategy {
        @Override
        public Restaurant select(List<Restaurant> candidates, List<String> items) {
            Restaurant best = null;
            for (Restaurant r : candidates) {
                if (best == null) { best = r; continue; }
                if (r.remainingCapacity() > best.remainingCapacity()) { best = r; }
                else if (r.remainingCapacity() == best.remainingCapacity()) {
                    long priceCurr = r.totalPrice(items);
                    long priceBest = best.totalPrice(items);
                    if (priceCurr < priceBest) { best = r; }
                    else if (priceCurr == priceBest) {
                        if (r.id.compareTo(best.id) < 0) { best = r; }
                    }
                }
            }
            return best;
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Map<String, Restaurant> restaurants = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final List<Order> dispatchedOrdersList = Collections.synchronizedList(new ArrayList<>());
    // restaurantId → (itemName → count)
    private final Map<String, Map<String, Integer>> servedCounts = new ConcurrentHashMap<>();

    private final Map<String, RestaurantSelectionStrategy> strategies = new HashMap<>();

    public FoodCart() {
        strategies.put("LOWEST_TOTAL_PRICE", new LowestTotalPriceStrategy());
        strategies.put("MAX_REMAINING_CAPACITY", new MaxRemainingCapacityStrategy());
    }

    // ═══════════════════════════════════════════════
    // Process Commands
    // ═══════════════════════════════════════════════

    public List<String> processCommands(List<String> commands) {
        // Create indexed commands for sorting by timestamp while preserving original positions
        int n = commands.size();
        String[] results = new String[n];

        // Parse and sort by timestamp (stable sort preserves relative order for same timestamp)
        int[][] indexed = new int[n][2]; // [originalIndex, sortKey]
        String[][] parsed = new String[n][];
        for (int i = 0; i < n; i++) {
            parsed[i] = commands.get(i).split("\\|");
            indexed[i][0] = i;
            indexed[i][1] = Integer.parseInt(parsed[i][0]);
        }

        // Sort by timestamp (stable)
        Arrays.sort(indexed, (a, b) -> {
            if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
            return Integer.compare(a[0], b[0]); // preserve input order on tie
        });

        // Execute in sorted order
        for (int[] idx : indexed) {
            int originalIdx = idx[0];
            String[] parts = parsed[originalIdx];
            String timestamp = parts[0];
            String command = parts[1];

            String result;
            switch (command) {
                case "ADD_RESTAURANT":
                    // Format: timestamp|ADD_RESTAURANT|restaurantId|capacity|menu
                    // Example: "100|ADD_RESTAURANT|R1|2|burger:120,pizza:200"
                    //   parts[0]="100", parts[1]="ADD_RESTAURANT", parts[2]="R1", parts[3]="2", parts[4]="burger:120,pizza:200"
                    result = addRestaurant(parts[2], Integer.parseInt(parts[3]), parts[4]);
                    break;
                case "UPDATE_MENU":
                    // Format: timestamp|UPDATE_MENU|restaurantId|menuUpdates
                    // Example: "205|UPDATE_MENU|R2|burger:130,pasta:150,salad:-1"
                    //   parts[2]="R2", parts[3]="burger:130,pasta:150,salad:-1"
                    //   price >= 0 → upsert, price < 0 → remove item
                    result = updateMenu(parts[2], parts[3]);
                    break;
                case "PLACE_ORDER":
                    // Format: timestamp|PLACE_ORDER|orderId|customerId|strategy|items
                    // Example: "200|PLACE_ORDER|O1|C1|LOWEST_TOTAL_PRICE|burger,pizza"
                    //   parts[2]="O1", parts[3]="C1", parts[4]="LOWEST_TOTAL_PRICE", parts[5]="burger,pizza"
                    result = placeOrder(parts[2], parts[3], parts[4], parts[5], timestamp);
                    break;
                case "DISPATCH_ORDER":
                    // Format: timestamp|DISPATCH_ORDER|orderId
                    // Example: "220|DISPATCH_ORDER|O1"
                    //   parts[2]="O1"
                    result = dispatchOrder(parts[2], timestamp);
                    break;
                default:
                    result = "UNKNOWN_COMMAND";
            }
            results[originalIdx] = result;
        }

        return Arrays.asList(results);
    }

    // ═══════════════════════════════════════════════
    // Command Implementations
    // ═══════════════════════════════════════════════

    private String addRestaurant(String restaurantId, int capacity, String menuStr) {
        Map<String, Long> menu = parseMenu(menuStr);
        Restaurant restaurant = new Restaurant(restaurantId, capacity, menu);
        // Atomic: only succeeds if key didn't exist
        if (restaurants.putIfAbsent(restaurantId, restaurant) != null) {
            return "RESTAURANT_ALREADY_EXISTS";
        }
        return "OK";
    }

    private String updateMenu(String restaurantId, String menuUpdates) {
        Restaurant restaurant = restaurants.get(restaurantId);
        if (restaurant == null) return "RESTAURANT_NOT_FOUND";

        // Lock restaurant: prevents reading stale menu during placeOrder
        restaurant.lock.lock();
        try {
            String[] items = menuUpdates.split(",");
            for (String item : items) {
                String[] parts = item.split(":");
                String name = parts[0];
                long price = Long.parseLong(parts[1]);

                if (price < 0) {
                    restaurant.menu.remove(name);
                } else {
                    restaurant.menu.put(name, price);
                }
            }
        } finally {
            restaurant.lock.unlock();
        }
        return "OK";
    }

    private String placeOrder(String orderId, String customerId, String strategy, String itemsStr, String timestamp) {
        RestaurantSelectionStrategy selectionStrategy = strategies.get(strategy);
        if (selectionStrategy == null) return "INVALID_STRATEGY";

        List<String> items = Arrays.asList(itemsStr.split(","));

        // Find all candidate restaurants (optimistic read — no locks held yet)
        List<Restaurant> candidates = new ArrayList<>();
        for (Restaurant r : restaurants.values()) {
            if (r.canAccept(items)) {
                candidates.add(r);
            }
        }

        if (candidates.isEmpty()) return "REJECTED";

        // Apply strategy to select best restaurant
        Restaurant selected = selectionStrategy.select(candidates, items);
        if (selected == null) return "REJECTED";

        // Lock the selected restaurant and RE-VERIFY (capacity/menu may have changed)
        // This is the optimistic-read → lock → re-verify pattern
        selected.lock.lock();
        try {
            if (!selected.canAccept(items)) return "REJECTED"; // re-verify under lock

            selected.openOrders++;
            Order order = new Order(orderId, customerId, selected.id, items, timestamp);
            orders.put(orderId, order);
            return "ACCEPTED";
        } finally {
            selected.lock.unlock();
        }
    }

    private String dispatchOrder(String orderId, String timestamp) {
        Order order = orders.get(orderId);
        if (order == null) return "INVALID_ORDER";
        if (order.dispatched) return "ALREADY_DISPATCHED";

        // Mark dispatched
        order.dispatched = true;
        order.dispatchTimestamp = timestamp;

        // Free capacity under restaurant lock
        Restaurant restaurant = restaurants.get(order.restaurantId);
        restaurant.lock.lock();
        try {
            restaurant.openOrders--;
        } finally {
            restaurant.lock.unlock();
        }

        // Track served items (ConcurrentHashMap handles concurrent puts)
        servedCounts.computeIfAbsent(order.restaurantId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> counts = servedCounts.get(order.restaurantId);
        for (String item : order.items) {
            counts.merge(item, 1, Integer::sum);
        }

        // Track dispatched orders
        dispatchedOrdersList.add(order);

        return "DISPATCHED";
    }

    // ═══════════════════════════════════════════════
    // Query Methods
    // ═══════════════════════════════════════════════

    public List<String> getRestaurantItemCounts() {
        List<String> results = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : servedCounts.entrySet()) {
            String restaurantId = entry.getKey();
            Map<String, Integer> counts = entry.getValue();
            for (Map.Entry<String, Integer> itemEntry : counts.entrySet()) {
                results.add(restaurantId + "|" + itemEntry.getKey() + "|" + itemEntry.getValue());
            }
        }

        // Sort lexicographically
        Collections.sort(results);
        return results;
    }

    public List<String> getDispatchedOrders(String restaurantId) {
        List<String> results = new ArrayList<>();

        for (Order order : dispatchedOrdersList) {
            if (order.restaurantId.equals(restaurantId)) {
                String items = String.join(",", order.items);
                results.add(order.dispatchTimestamp + "|" + order.orderId + "|" + order.customerId + "|" + items);
            }
        }

        return results;
    }

    // ═══════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════

    private Map<String, Long> parseMenu(String menuStr) {
        Map<String, Long> menu = new HashMap<>();
        String[] items = menuStr.split(",");
        for (String item : items) {
            String[] parts = item.split(":");
            menu.put(parts[0], Long.parseLong(parts[1]));
        }
        return menu;
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        FoodCart foodCart = new FoodCart();

        // Example 1: Commands in random timestamp order
        System.out.println("═══ Example 1: Process Commands ═══\n");

        List<String> commands = Arrays.asList(
            "200|PLACE_ORDER|O1|C1|LOWEST_TOTAL_PRICE|burger,pizza",
            "100|ADD_RESTAURANT|R1|2|burger:120,pizza:200",
            "150|ADD_RESTAURANT|R2|1|burger:110,pizza:220",
            "210|PLACE_ORDER|O2|C2|LOWEST_TOTAL_PRICE|burger",
            "220|DISPATCH_ORDER|O1",
            "205|UPDATE_MENU|R2|pizza:180",
            "230|PLACE_ORDER|O3|C3|LOWEST_TOTAL_PRICE|pizza"
        );

        List<String> outputs = foodCart.processCommands(commands);

        System.out.println("Commands and outputs:");
        for (int i = 0; i < commands.size(); i++) {
            System.out.println("  " + commands.get(i));
            System.out.println("    → " + outputs.get(i));
        }

        // Verify expected output
        List<String> expected = Arrays.asList("ACCEPTED", "OK", "OK", "ACCEPTED", "DISPATCHED", "OK", "ACCEPTED");
        System.out.println("\nExpected: " + expected);
        System.out.println("Got:      " + outputs);
        System.out.println("Match:    " + outputs.equals(expected));

        // Example 2: Items served per restaurant
        System.out.println("\n═══ Example 2: Restaurant Item Counts ═══\n");
        List<String> counts = foodCart.getRestaurantItemCounts();
        counts.forEach(s -> System.out.println("  " + s));

        // Example 3: Dispatched orders for R1
        System.out.println("\n═══ Example 3: Dispatched Orders (R1) ═══\n");
        List<String> dispatched = foodCart.getDispatchedOrders("R1");
        dispatched.forEach(s -> System.out.println("  " + s));

        // ─── Additional: MAX_REMAINING_CAPACITY strategy ───
        System.out.println("\n═══ Example 4: MAX_REMAINING_CAPACITY ═══\n");

        FoodCart cart2 = new FoodCart();
        List<String> cmds2 = Arrays.asList(
            "100|ADD_RESTAURANT|R1|5|burger:120,pizza:200",
            "101|ADD_RESTAURANT|R2|3|burger:110,pizza:180",
            "200|PLACE_ORDER|O1|C1|MAX_REMAINING_CAPACITY|burger,pizza",
            "201|PLACE_ORDER|O2|C2|MAX_REMAINING_CAPACITY|burger",
            "202|PLACE_ORDER|O3|C3|LOWEST_TOTAL_PRICE|burger"
        );

        List<String> out2 = cart2.processCommands(cmds2);
        for (int i = 0; i < cmds2.size(); i++) {
            System.out.println("  " + cmds2.get(i));
            System.out.println("    → " + out2.get(i));
        }

        // ─── Edge cases ───
        System.out.println("\n═══ Edge Cases ═══\n");

        FoodCart cart3 = new FoodCart();
        List<String> cmds3 = Arrays.asList(
            "100|ADD_RESTAURANT|R1|1|burger:100",
            "100|ADD_RESTAURANT|R1|2|pizza:200",     // duplicate
            "200|PLACE_ORDER|O1|C1|LOWEST_TOTAL_PRICE|burger",
            "201|PLACE_ORDER|O2|C2|LOWEST_TOTAL_PRICE|burger",  // capacity full
            "202|PLACE_ORDER|O3|C3|LOWEST_TOTAL_PRICE|sushi",   // item not found
            "203|PLACE_ORDER|O4|C4|RANDOM_STRATEGY|burger",     // invalid strategy
            "300|DISPATCH_ORDER|O1",
            "301|DISPATCH_ORDER|O1",     // already dispatched
            "302|DISPATCH_ORDER|O99"     // invalid order
        );

        List<String> out3 = cart3.processCommands(cmds3);
        for (int i = 0; i < cmds3.size(); i++) {
            System.out.println("  " + cmds3.get(i));
            System.out.println("    → " + out3.get(i));
        }
    }
}
