package inventory.exception;

public class WarehouseNotFoundException extends RuntimeException {
    public WarehouseNotFoundException(String warehouseId) {
        super("Warehouse not found: " + warehouseId);
    }
}
