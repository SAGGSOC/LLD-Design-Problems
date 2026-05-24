package inventory.service;

import inventory.exception.WarehouseNotFoundException;
import inventory.model.Warehouse;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WarehouseService {
    private final Map<String, Warehouse> warehouses = new ConcurrentHashMap<>();

    public Warehouse addWarehouse(Warehouse warehouse) {
        warehouses.put(warehouse.getWarehouseId(), warehouse);
        return warehouse;
    }

    public Warehouse getWarehouse(String warehouseId) {
        Warehouse w = warehouses.get(warehouseId);
        if (w == null) throw new WarehouseNotFoundException(warehouseId);
        return w;
    }

    public Collection<Warehouse> getAllWarehouses() {
        return warehouses.values();
    }
}
