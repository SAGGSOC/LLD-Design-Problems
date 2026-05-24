package inventory.model;

import java.time.LocalDate;

public class Batch {
    private final String batchId;
    private final String productId;
    private int quantity;
    private final LocalDate manufactureDate;
    private final LocalDate expiryDate;

    public Batch(String batchId, String productId, int quantity,
                 LocalDate manufactureDate, LocalDate expiryDate) {
        this.batchId = batchId;
        this.productId = productId;
        this.quantity = quantity;
        this.manufactureDate = manufactureDate;
        this.expiryDate = expiryDate;
    }

    public boolean isExpired() {
        return expiryDate != null && LocalDate.now().isAfter(expiryDate);
    }

    public boolean isExpiringSoon(int daysThreshold) {
        if (expiryDate == null) return false;
        return LocalDate.now().plusDays(daysThreshold).isAfter(expiryDate) && !isExpired();
    }

    public synchronized void addQuantity(int amount) {
        this.quantity += amount;
    }

    public synchronized void removeQuantity(int amount) {
        if (amount > quantity) {
            throw new IllegalArgumentException("Cannot remove " + amount + " from batch " + batchId
                    + " (available: " + quantity + ")");
        }
        this.quantity -= amount;
    }

    public String getBatchId()           { return batchId; }
    public String getProductId()         { return productId; }
    public int getQuantity()             { return quantity; }
    public LocalDate getManufactureDate() { return manufactureDate; }
    public LocalDate getExpiryDate()     { return expiryDate; }

    @Override
    public String toString() {
        return String.format("Batch[%s, qty=%d, expires=%s]", batchId, quantity, expiryDate);
    }
}
