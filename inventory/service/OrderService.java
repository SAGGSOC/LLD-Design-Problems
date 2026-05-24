package inventory.service;

import inventory.enums.OrderStatus;
import inventory.model.Order;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class OrderService {
    private final StockService stockService;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong orderCounter = new AtomicLong(1);

    public OrderService(StockService stockService) {
        this.stockService = stockService;
    }

    public Order createOrder(String warehouseId, java.util.List<Order.OrderLine> lines) {
        String orderId = "ORD-" + String.format("%06d", orderCounter.getAndIncrement());
        Order order = new Order(orderId, warehouseId, lines);
        orders.put(orderId, order);
        return order;
    }

    /**
     * Fulfills an order by removing stock for each line item.
     * If any line fails, the order is marked CANCELLED (no partial fulfillment).
     */
    public Order fulfillOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("Order not found: " + orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Order " + orderId + " is not pending: " + order.getStatus());
        }

        order.setStatus(OrderStatus.PROCESSING);

        try {
            for (Order.OrderLine line : order.getLines()) {
                stockService.removeStock(order.getWarehouseId(), line.getProductId(), line.getQuantity());
            }
            order.setStatus(OrderStatus.FULFILLED);
            order.setFulfilledAt(Instant.now());
        } catch (Exception e) {
            order.setStatus(OrderStatus.CANCELLED);
            throw e;
        }

        return order;
    }

    public Order getOrder(String orderId) {
        return orders.get(orderId);
    }
}
