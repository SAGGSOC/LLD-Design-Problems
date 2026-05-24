package inventory.service;

import inventory.exception.InsufficientStockException;
import inventory.model.Batch;
import inventory.model.Product;
import inventory.model.StockEntry;
import inventory.model.Warehouse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class StockService {
    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final AtomicLong batchCounter = new AtomicLong(1);

    public StockService(WarehouseService warehouseService, ProductService productService) {
        this.warehouseService = warehouseService;
        this.productService = productService;
    }

    public Batch addStock(String warehouseId, String productId, int quantity,
                          LocalDate manufactureDate, LocalDate expiryDate) {
        Warehouse warehouse = warehouseService.getWarehouse(warehouseId);
        productService.getProduct(productId); // validate exists

        String batchId = "BAT-" + String.format("%06d", batchCounter.getAndIncrement());
        Batch batch = new Batch(batchId, productId, quantity, manufactureDate, expiryDate);

        StockEntry entry = warehouse.getOrCreateStockEntry(productId);
        entry.addBatch(batch);
        return batch;
    }

    public void removeStock(String warehouseId, String productId, int quantity) {
        Warehouse warehouse = warehouseService.getWarehouse(warehouseId);
        StockEntry entry = warehouse.getStockEntry(productId);

        if (entry == null || entry.getTotalQuantity() < quantity) {
            int available = entry != null ? entry.getTotalQuantity() : 0;
            throw new InsufficientStockException(productId, warehouseId, quantity, available);
        }

        entry.removeQuantity(quantity); // FEFO removal
    }

    public int getStockLevel(String warehouseId, String productId) {
        Warehouse warehouse = warehouseService.getWarehouse(warehouseId);
        return warehouse.getStockLevel(productId);
    }

    public Map<String, Integer> getWarehouseStockLevels(String warehouseId) {
        Warehouse warehouse = warehouseService.getWarehouse(warehouseId);
        return warehouse.getAllStockLevels();
    }

    public List<LowStockAlert> getLowStockAlerts() {
        List<LowStockAlert> alerts = new ArrayList<>();
        Map<String, Product> catalog = productService.getProductMap();

        for (Warehouse warehouse : warehouseService.getAllWarehouses()) {
            for (String productId : warehouse.getLowStockProducts(catalog)) {
                Product product = catalog.get(productId);
                int currentStock = warehouse.getStockLevel(productId);
                alerts.add(new LowStockAlert(
                        warehouse.getWarehouseId(), productId, product.getName(),
                        currentStock, product.getLowStockThreshold()));
            }
        }
        return alerts;
    }

    public List<Batch> getExpiringSoonBatches(String warehouseId, int daysThreshold) {
        Warehouse warehouse = warehouseService.getWarehouse(warehouseId);
        List<Batch> expiring = new ArrayList<>();
        for (Map.Entry<String, Integer> e : warehouse.getAllStockLevels().entrySet()) {
            StockEntry entry = warehouse.getStockEntry(e.getKey());
            if (entry != null) {
                expiring.addAll(entry.getExpiringSoonBatches(daysThreshold));
            }
        }
        return expiring;
    }

    public static class LowStockAlert {
        public final String warehouseId;
        public final String productId;
        public final String productName;
        public final int currentStock;
        public final int threshold;

        public LowStockAlert(String warehouseId, String productId, String productName,
                              int currentStock, int threshold) {
            this.warehouseId = warehouseId;
            this.productId = productId;
            this.productName = productName;
            this.currentStock = currentStock;
            this.threshold = threshold;
        }

        @Override
        public String toString() {
            return String.format("LOW STOCK: %s (%s) at %s — %d units (threshold: %d)",
                    productName, productId, warehouseId, currentStock, threshold);
        }
    }
}
