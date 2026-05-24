package deliverycost.model;

/**
 * Represents a payment made up to a certain time.
 * Tracks how much was paid and the cutoff time.
 */
public class Payment {
    private final String paymentId;
    private final int paidUpToTime;   // all deliveries ending <= this time are considered paid
    private final double amount;

    public Payment(String paymentId, int paidUpToTime, double amount) {
        this.paymentId = paymentId;
        this.paidUpToTime = paidUpToTime;
        this.amount = amount;
    }

    public String getPaymentId() { return paymentId; }
    public int getPaidUpToTime() { return paidUpToTime; }
    public double getAmount() { return amount; }
}
