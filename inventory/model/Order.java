package inventory.model;

import inventory.enums.OrderStatus;
import java.time.Instant;
import java.util.List;

public class Order {
    private final String orderId;
    private final String warehouseId;
    private final List<OrderLine> lines;
    private final Instant createdAt;
    private OrderStatus status;
    private Instant fulfilledAt;

    public Order(String orderId, String warehouseId, List<OrderLine> lines) {
        this.orderId = orderId;
        this.warehouseId = warehouseId;
        this.lines = lines;
        this.createdAt = Instant.now();
        this.status = OrderStatus.PENDING;
    }

    public String getOrderId()         { return orderId; }
    public String getWarehouseId()     { return warehouseId; }
    public List<OrderLine> getLines()  { return lines; }
    public Instant getCreatedAt()      { return createdAt; }
    public OrderStatus getStatus()     { return status; }
    public Instant getFulfilledAt()    { return fulfilledAt; }

    public void setStatus(OrderStatus status)     { this.status = status; }
    public void setFulfilledAt(Instant fulfilledAt) { this.fulfilledAt = fulfilledAt; }

    @Override
    public String toString() {
        return String.format("Order[%s, warehouse=%s, lines=%d, status=%s]",
                orderId, warehouseId, lines.size(), status);
    }

    public static class OrderLine {
        private final String productId;
        private final int quantity;

        public OrderLine(String productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public String getProductId() { return productId; }
        public int getQuantity()     { return quantity; }
    }
}
