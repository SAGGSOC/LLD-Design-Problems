package inventory.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tracks all batches of a single product at a single warehouse.
 * Uses FEFO (First Expired, First Out) for removal.
 */
public class StockEntry {
    private final String productId;
    private final String warehouseId;
    private final List<Batch> batches = new ArrayList<>();

    public StockEntry(String productId, String warehouseId) {
        this.productId = productId;
        this.warehouseId = warehouseId;
    }

    public synchronized void addBatch(Batch batch) {
        batches.add(batch);
    }

    /**
     * Removes quantity using FEFO — earliest expiry first.
     * Skips expired batches.
     */
    public synchronized List<Batch> removeQuantity(int amount) {
        List<Batch> sorted = batches.stream()
                .filter(b -> !b.isExpired() && b.getQuantity() > 0)
                .sorted(Comparator.comparing(b -> b.getExpiryDate() == null
                        ? java.time.LocalDate.MAX : b.getExpiryDate()))
                .collect(Collectors.toList());

        int remaining = amount;
        List<Batch> usedBatches = new ArrayList<>();

        for (Batch batch : sorted) {
            if (remaining <= 0) break;
            int take = Math.min(remaining, batch.getQuantity());
            batch.removeQuantity(take);
            remaining -= take;
            usedBatches.add(batch);
        }

        if (remaining > 0) {
            throw new IllegalStateException("Could not fulfill removal of " + amount
                    + " units for product " + productId + " at warehouse " + warehouseId);
        }

        return usedBatches;
    }

    public int getTotalQuantity() {
        return batches.stream()
                .filter(b -> !b.isExpired())
                .mapToInt(Batch::getQuantity).sum();
    }

    public List<Batch> getExpiredBatches() {
        return batches.stream().filter(Batch::isExpired).collect(Collectors.toList());
    }

    public List<Batch> getExpiringSoonBatches(int days) {
        return batches.stream().filter(b -> b.isExpiringSoon(days)).collect(Collectors.toList());
    }

    public String getProductId()   { return productId; }
    public String getWarehouseId() { return warehouseId; }
    public List<Batch> getBatches() { return batches; }
}
