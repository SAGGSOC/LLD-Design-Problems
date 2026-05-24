package inventory.model;

import inventory.enums.TransferStatus;
import java.time.Instant;

public class Transfer {
    private final String transferId;
    private final String productId;
    private final String fromWarehouseId;
    private final String toWarehouseId;
    private final int quantity;
    private final Instant initiatedAt;
    private TransferStatus status;
    private Instant completedAt;

    public Transfer(String transferId, String productId,
                    String fromWarehouseId, String toWarehouseId, int quantity) {
        this.transferId = transferId;
        this.productId = productId;
        this.fromWarehouseId = fromWarehouseId;
        this.toWarehouseId = toWarehouseId;
        this.quantity = quantity;
        this.initiatedAt = Instant.now();
        this.status = TransferStatus.INITIATED;
    }

    public String getTransferId()      { return transferId; }
    public String getProductId()       { return productId; }
    public String getFromWarehouseId() { return fromWarehouseId; }
    public String getToWarehouseId()   { return toWarehouseId; }
    public int getQuantity()           { return quantity; }
    public Instant getInitiatedAt()    { return initiatedAt; }
    public TransferStatus getStatus()  { return status; }
    public Instant getCompletedAt()    { return completedAt; }

    public void setStatus(TransferStatus status)       { this.status = status; }
    public void setCompletedAt(Instant completedAt)    { this.completedAt = completedAt; }

    @Override
    public String toString() {
        return String.format("Transfer[%s: %s, %d units, %s → %s, %s]",
                transferId, productId, quantity, fromWarehouseId, toWarehouseId, status);
    }
}
