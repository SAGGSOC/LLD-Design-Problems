import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory Management — interview-ready, single-file (~170 lines).
 *
 * Multi-warehouse e-commerce inventory with atomic reserve/commit for order fulfillment.
 *
 * Scope:
 *   - Products and SKUs
 *   - Per-warehouse stock levels
 *   - Reserve → commit → release lifecycle (matches real order → ship → cancel flow)
 *   - Low-stock threshold alerting
 *
 * Out of scope: purchase orders, supplier integration, batch/lot tracking, ABC analysis
 *
 * Concurrency: synchronized on Stock (per warehouse + SKU). Each stock row is
 * independently lockable — no global lock.
 */
public class Inventory {

    static class Product {
        final String sku;
        final String name;
        final double price;
        final int lowStockThreshold;
        Product(String sku, String name, double price, int lowStockThreshold) {
            this.sku = sku; this.name = name; this.price = price;
            this.lowStockThreshold = lowStockThreshold;
        }
    }

    static class Warehouse {
        final String warehouseId;
        final String city;
        Warehouse(String warehouseId, String city) { this.warehouseId = warehouseId; this.city = city; }
    }

    /** Stock per (warehouse, sku). available = onHand - reserved. */
    static class Stock {
        final String warehouseId;
        final String sku;
        int onHand;    // physically present
        int reserved;  // allocated to pending orders

        Stock(String warehouseId, String sku, int onHand) {
            this.warehouseId = warehouseId; this.sku = sku; this.onHand = onHand;
        }

        int available() { return onHand - reserved; }
    }

    static class Reservation {
        final String reservationId;
        final String orderId;
        final List<Line> lines;  // each line is (warehouse, sku, qty)
        boolean committed = false;
        boolean released = false;
        final Instant createdAt = Instant.now();

        Reservation(String reservationId, String orderId, List<Line> lines) {
            this.reservationId = reservationId; this.orderId = orderId; this.lines = lines;
        }
    }

    static class Line {
        final String warehouseId;
        final String sku;
        final int quantity;
        Line(String warehouseId, String sku, int quantity) {
            this.warehouseId = warehouseId; this.sku = sku; this.quantity = quantity;
        }
    }

    // ─── Service ───

    static class InventoryService {
        final Map<String, Product> productsBySku = new HashMap<>();
        final Map<String, Warehouse> warehouses = new HashMap<>();
        final Map<String, Stock> stockByKey = new ConcurrentHashMap<>();  // key = wh#sku
        final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
        final List<String> lowStockAlerts = Collections.synchronizedList(new ArrayList<>());

        void addProduct(Product p)                          { productsBySku.put(p.sku, p); }
        void addWarehouse(Warehouse w)                      { warehouses.put(w.warehouseId, w); }
        void addStock(String warehouseId, String sku, int qty) {
            stockByKey.put(key(warehouseId, sku), new Stock(warehouseId, sku, qty));
        }

        String key(String wh, String sku) { return wh + "#" + sku; }

        /**
         * Atomically reserve all lines. All-or-nothing — if any line fails, roll back.
         * Sort keys to prevent deadlock (same trick as in BookMyShow).
         */
        Reservation reserveForOrder(String orderId, List<Line> lines) {
            List<Line> sorted = new ArrayList<>(lines);
            sorted.sort(Comparator.comparing((Line l) -> key(l.warehouseId, l.sku)));

            List<Line> reserved = new ArrayList<>();
            try {
                for (Line line : sorted) {
                    Stock stock = stockByKey.get(key(line.warehouseId, line.sku));
                    if (stock == null) throw new RuntimeException("No stock row: " + line.sku);
                    synchronized (stock) {
                        if (stock.available() < line.quantity) {
                            throw new RuntimeException("Out of stock: " + line.sku
                                + " (want " + line.quantity + ", available " + stock.available() + ")");
                        }
                        stock.reserved += line.quantity;
                    }
                    reserved.add(line);
                }

                String resId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
                Reservation reservation = new Reservation(resId, orderId, sorted);
                reservations.put(resId, reservation);
                return reservation;
            } catch (RuntimeException e) {
                // Rollback everything we reserved in this call
                for (Line line : reserved) {
                    Stock stock = stockByKey.get(key(line.warehouseId, line.sku));
                    synchronized (stock) { stock.reserved -= line.quantity; }
                }
                throw e;
            }
        }

        /** Commit: stock physically leaves the warehouse (ship). onHand -= qty, reserved -= qty. */
        void commitReservation(String reservationId) {
            Reservation r = reservations.get(reservationId);
            if (r == null || r.committed || r.released) throw new RuntimeException("Invalid reservation");

            for (Line line : r.lines) {
                Stock stock = stockByKey.get(key(line.warehouseId, line.sku));
                synchronized (stock) {
                    stock.onHand -= line.quantity;
                    stock.reserved -= line.quantity;
                    checkLowStock(stock);
                }
            }
            r.committed = true;
        }

        /** Release: stock returns to available (order cancelled). reserved -= qty. */
        void releaseReservation(String reservationId) {
            Reservation r = reservations.get(reservationId);
            if (r == null || r.committed || r.released) throw new RuntimeException("Invalid reservation");

            for (Line line : r.lines) {
                Stock stock = stockByKey.get(key(line.warehouseId, line.sku));
                synchronized (stock) { stock.reserved -= line.quantity; }
            }
            r.released = true;
        }

        /** Restock: direct inbound (from supplier). onHand += qty. */
        void restock(String warehouseId, String sku, int qty) {
            Stock stock = stockByKey.get(key(warehouseId, sku));
            synchronized (stock) { stock.onHand += qty; }
        }

        int getAvailable(String warehouseId, String sku) {
            Stock stock = stockByKey.get(key(warehouseId, sku));
            return stock == null ? 0 : stock.available();
        }

        private void checkLowStock(Stock stock) {
            Product p = productsBySku.get(stock.sku);
            if (p != null && stock.available() <= p.lowStockThreshold) {
                String alert = "LOW STOCK: " + stock.sku + " @ " + stock.warehouseId
                    + " (available=" + stock.available() + ", threshold=" + p.lowStockThreshold + ")";
                lowStockAlerts.add(alert);
                System.out.println("  [ALERT] " + alert);
            }
        }
    }

    // ─── Demo ───

    public static void main(String[] args) throws Exception {
        InventoryService service = new InventoryService();
        service.addProduct(new Product("SKU-LAPTOP", "Laptop",  1200.00, 3));
        service.addProduct(new Product("SKU-MOUSE",  "Mouse",     25.00, 10));
        service.addWarehouse(new Warehouse("WH-SEA", "Seattle"));
        service.addWarehouse(new Warehouse("WH-NYC", "New York"));

        service.addStock("WH-SEA", "SKU-LAPTOP", 5);
        service.addStock("WH-NYC", "SKU-LAPTOP", 2);
        service.addStock("WH-SEA", "SKU-MOUSE",  50);

        // Happy path
        Reservation r1 = service.reserveForOrder("ORD-1", Arrays.asList(
            new Line("WH-SEA", "SKU-LAPTOP", 2),
            new Line("WH-SEA", "SKU-MOUSE", 1)));
        System.out.println("Reserved ORD-1. SEA laptops available: "
            + service.getAvailable("WH-SEA", "SKU-LAPTOP") + " (was 5)");

        service.commitReservation(r1.reservationId);
        System.out.println("Shipped. SEA laptops onHand: "
            + service.getAvailable("WH-SEA", "SKU-LAPTOP"));

        // Cancelled order returns stock
        Reservation r2 = service.reserveForOrder("ORD-2", Collections.singletonList(
            new Line("WH-NYC", "SKU-LAPTOP", 1)));
        System.out.println("\nReserved ORD-2. NYC available: "
            + service.getAvailable("WH-NYC", "SKU-LAPTOP") + " (was 2)");
        service.releaseReservation(r2.reservationId);
        System.out.println("Cancelled. NYC available: "
            + service.getAvailable("WH-NYC", "SKU-LAPTOP") + " (back to 2)");

        // All-or-nothing: one line out of stock fails the whole order, rolls back the other
        System.out.println("\n--- All-or-nothing rollback ---");
        int mouseBefore = service.getAvailable("WH-SEA", "SKU-MOUSE");
        try {
            service.reserveForOrder("ORD-BAD", Arrays.asList(
                new Line("WH-SEA", "SKU-MOUSE",  3),   // available
                new Line("WH-SEA", "SKU-LAPTOP", 100)));  // not available
        } catch (Exception e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        int mouseAfter = service.getAvailable("WH-SEA", "SKU-MOUSE");
        System.out.println("  Mouse rolled back: " + mouseBefore + " → " + mouseAfter);

        // Concurrent race: 10 threads try to reserve 1 laptop from NYC (has 2 → expect 2 wins)
        System.out.println("\n--- Concurrent race for NYC laptops ---");
        int[] wins = {0};
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            String orderId = "ORD-RACE-" + i;
            threads[i] = new Thread(() -> {
                try {
                    service.reserveForOrder(orderId, Collections.singletonList(
                        new Line("WH-NYC", "SKU-LAPTOP", 1)));
                    synchronized (wins) { wins[0]++; }
                } catch (Exception ignored) {}
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        System.out.println("  Winners: " + wins[0] + " (expected 2)");
        System.out.println("  NYC available: " + service.getAvailable("WH-NYC", "SKU-LAPTOP"));

        // Low-stock alert
        System.out.println("\n--- Low-stock alert ---");
        Reservation r3 = service.reserveForOrder("ORD-ALERT", Collections.singletonList(
            new Line("WH-SEA", "SKU-LAPTOP", 2)));
        service.commitReservation(r3.reservationId);
    }
}
