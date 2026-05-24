package inventory.service;

import inventory.enums.TransferStatus;
import inventory.model.Transfer;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TransferService {
    private final StockService stockService;
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final AtomicLong transferCounter = new AtomicLong(1);

    public TransferService(StockService stockService) {
        this.stockService = stockService;
    }

    /**
     * Initiates a transfer: removes stock from source warehouse immediately,
     * marks transfer as IN_TRANSIT.
     */
    public Transfer initiateTransfer(String productId, String fromWarehouseId,
                                      String toWarehouseId, int quantity) {
        // Remove from source (validates stock availability)
        stockService.removeStock(fromWarehouseId, productId, quantity);

        String transferId = "TRF-" + String.format("%06d", transferCounter.getAndIncrement());
        Transfer transfer = new Transfer(transferId, productId, fromWarehouseId, toWarehouseId, quantity);
        transfer.setStatus(TransferStatus.IN_TRANSIT);
        transfers.put(transferId, transfer);
        return transfer;
    }

    /**
     * Completes a transfer: adds stock to destination warehouse.
     */
    public Transfer completeTransfer(String transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer == null) throw new IllegalArgumentException("Transfer not found: " + transferId);
        if (transfer.getStatus() != TransferStatus.IN_TRANSIT) {
            throw new IllegalStateException("Transfer " + transferId + " is not in transit: " + transfer.getStatus());
        }

        // Add to destination — creates a new batch at the destination
        stockService.addStock(transfer.getToWarehouseId(), transfer.getProductId(),
                transfer.getQuantity(), java.time.LocalDate.now(), null);

        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setCompletedAt(Instant.now());
        return transfer;
    }

    public Transfer getTransfer(String transferId) {
        return transfers.get(transferId);
    }
}
