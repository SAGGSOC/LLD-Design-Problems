package inventory.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String productId, String warehouseId, int requested, int available) {
        super(String.format("Insufficient stock for product %s at warehouse %s: requested %d, available %d",
                productId, warehouseId, requested, available));
    }
}
