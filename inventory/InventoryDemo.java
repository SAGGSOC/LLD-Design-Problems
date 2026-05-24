package inventory;

import inventory.enums.Category;
import inventory.model.*;
import inventory.service.*;

import java.time.LocalDate;
import java.util.List;

public class InventoryDemo {

    public static void main(String[] args) {
        // --- Setup services ---
        ProductService productService = new ProductService();
        WarehouseService warehouseService = new WarehouseService();
        StockService stockService = new StockService(warehouseService, productService);
        TransferService transferService = new TransferService(stockService);
        OrderService orderService = new OrderService(stockService);

        // --- Create products ---
        Product laptop = productService.addProduct(
                new Product("P001", "Laptop", Category.ELECTRONICS, 999.99, 10));
        Product milk = productService.addProduct(
                new Product("P002", "Milk 1L", Category.FOOD, 3.49, 50));
        Product tshirt = productService.addProduct(
                new Product("P003", "T-Shirt", Category.CLOTHING, 19.99, 20));

        System.out.println("=== Products ===");
        productService.getAllProducts().forEach(System.out::println);
        System.out.println();

        // --- Create warehouses ---
        Warehouse wh1 = warehouseService.addWarehouse(
                new Warehouse("WH-001", "Main Warehouse", "Seattle"));
        Warehouse wh2 = warehouseService.addWarehouse(
                new Warehouse("WH-002", "East Coast Hub", "New York"));

        System.out.println("=== Warehouses ===");
        warehouseService.getAllWarehouses().forEach(System.out::println);
        System.out.println();

        // --- Add stock with batches ---
        System.out.println("=== Adding Stock ===");
        Batch b1 = stockService.addStock("WH-001", "P001", 50,
                LocalDate.of(2025, 1, 1), null); // laptops don't expire
        System.out.println("Added: " + b1);

        Batch b2 = stockService.addStock("WH-001", "P002", 200,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 20));
        System.out.println("Added: " + b2);

        Batch b3 = stockService.addStock("WH-001", "P002", 100,
                LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 15));
        System.out.println("Added: " + b3);

        Batch b4 = stockService.addStock("WH-001", "P003", 15,
                LocalDate.of(2025, 2, 1), null);
        System.out.println("Added: " + b4);

        Batch b5 = stockService.addStock("WH-002", "P001", 30,
                LocalDate.of(2025, 2, 1), null);
        System.out.println("Added: " + b5);
        System.out.println();

        // --- Check stock levels ---
        System.out.println("=== Stock Levels (WH-001) ===");
        stockService.getWarehouseStockLevels("WH-001")
                .forEach((pid, qty) -> System.out.println("  " + pid + ": " + qty + " units"));
        System.out.println();

        // --- Low stock alerts ---
        System.out.println("=== Low Stock Alerts ===");
        List<StockService.LowStockAlert> alerts = stockService.getLowStockAlerts();
        if (alerts.isEmpty()) {
            System.out.println("  No low stock alerts");
        } else {
            alerts.forEach(a -> System.out.println("  " + a));
        }
        System.out.println();

        // --- Transfer stock between warehouses ---
        System.out.println("=== Transfer: 10 Laptops from WH-001 → WH-002 ===");
        Transfer transfer = transferService.initiateTransfer("P001", "WH-001", "WH-002", 10);
        System.out.println("Initiated: " + transfer);
        System.out.println("WH-001 laptops: " + stockService.getStockLevel("WH-001", "P001"));
        System.out.println("WH-002 laptops: " + stockService.getStockLevel("WH-002", "P001"));

        transferService.completeTransfer(transfer.getTransferId());
        System.out.println("Completed: " + transfer);
        System.out.println("WH-001 laptops: " + stockService.getStockLevel("WH-001", "P001"));
        System.out.println("WH-002 laptops: " + stockService.getStockLevel("WH-002", "P001"));
        System.out.println();

        // --- Fulfill an order ---
        System.out.println("=== Order Fulfillment ===");
        Order order = orderService.createOrder("WH-001", List.of(
                new Order.OrderLine("P001", 5),
                new Order.OrderLine("P002", 50)
        ));
        System.out.println("Created: " + order);

        orderService.fulfillOrder(order.getOrderId());
        System.out.println("Fulfilled: " + order);
        System.out.println("WH-001 laptops after order: " + stockService.getStockLevel("WH-001", "P001"));
        System.out.println("WH-001 milk after order: " + stockService.getStockLevel("WH-001", "P002"));
        System.out.println();

        // --- Check low stock after operations ---
        System.out.println("=== Low Stock Alerts (after operations) ===");
        stockService.getLowStockAlerts().forEach(a -> System.out.println("  " + a));
        System.out.println();

        // --- Expiring soon check ---
        System.out.println("=== Batches Expiring Within 30 Days (WH-001) ===");
        List<Batch> expiring = stockService.getExpiringSoonBatches("WH-001", 30);
        if (expiring.isEmpty()) {
            System.out.println("  None expiring soon");
        } else {
            expiring.forEach(b -> System.out.println("  " + b));
        }
    }
}
