package inventory.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Warehouse {
    private final String warehouseId;
    private final String name;
    private final String location;
    private final Map<String, StockEntry> stock = new ConcurrentHashMap<>();

    public Warehouse(String warehouseId, String name, String location) {
        this.warehouseId = warehouseId;
        this.name = name;
        this.location = location;
    }

    public StockEntry getOrCreateStockEntry(String productId) {
        return stock.computeIfAbsent(productId, pid -> new StockEntry(pid, warehouseId));
    }

    public StockEntry getStockEntry(String productId) {
        return stock.get(productId);
    }

    public int getStockLevel(String productId) {
        StockEntry entry = stock.get(productId);
        return entry != null ? entry.getTotalQuantity() : 0;
    }

    public Map<String, Integer> getAllStockLevels() {
        return stock.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getTotalQuantity()));
    }

    public List<String> getLowStockProducts(Map<String, Product> productCatalog) {
        return stock.entrySet().stream()
                .filter(e -> {
                    Product p = productCatalog.get(e.getKey());
                    return p != null && e.getValue().getTotalQuantity() <= p.getLowStockThreshold();
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public String getWarehouseId() { return warehouseId; }
    public String getName()        { return name; }
    public String getLocation()    { return location; }

    @Override
    public String toString() {
        return String.format("Warehouse[%s: %s, %s]", warehouseId, name, location);
    }
}
