import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Billing & Discounts System — In-Memory (Interview Style)
 *
 * Features:
 *   - Create bills with cart items (sequential IDs: B1, B2, ...)
 *   - Apply discount codes: P10, P20, FLAT100, REDEEM
 *   - Pay bill (exact amount match required)
 *   - Loyalty points + level system (BRONZE/SILVER/GOLD/PLATINUM)
 *
 * Discount computation order:
 *   1. Effective percentage (max of P10/P20)
 *   2. FLAT100 (only if subtotal >= 500)
 *   3. REDEEM (capped at 20% of current payable, 1 point = $1)
 *
 * Design:
 *   - Strategy pattern potential for discount codes
 *   - Deterministic: integer math, floor division, no floating point
 *   - Idempotent discount application (set-based, not stack-based)
 */
public class BillingSystem {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class Customer {
        final String customerId;
        long points;
        final ReentrantLock lock = new ReentrantLock(); // protects points read/write

        Customer(String customerId) {
            this.customerId = customerId;
            this.points = 0;
        }

        String getLevel() {
            if (points >= 2000) return "PLATINUM";
            if (points >= 500) return "GOLD";
            if (points >= 100) return "SILVER";
            return "BRONZE";
        }
    }

    static class Bill {
        final String billId;
        final String customerId;
        final long subtotal;
        final Set<String> appliedDiscounts;
        boolean paid;
        final ReentrantLock lock = new ReentrantLock(); // protects discount set + paid flag

        Bill(String billId, String customerId, long subtotal) {
            this.billId = billId;
            this.customerId = customerId;
            this.subtotal = subtotal;
            this.appliedDiscounts = new LinkedHashSet<>();
            this.paid = false;
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Map<String, Customer> customers = new ConcurrentHashMap<>();
    private final Map<String, Bill> bills = new ConcurrentHashMap<>();
    private final AtomicInteger billCounter = new AtomicInteger(0); // thread-safe sequential IDs
    private static final Set<String> VALID_CODES = new HashSet<>(Arrays.asList("P10", "P20", "FLAT100", "REDEEM"));

    // ═══════════════════════════════════════════════
    // 1. Create Bill
    // ═══════════════════════════════════════════════

    public String createBill(String customerId, List<String> cartItems) {
        // Validate
        if (customerId == null || customerId.isEmpty()) return "ERROR";
        if (cartItems == null || cartItems.isEmpty()) return "ERROR";

        long subtotal = 0;
        for (String item : cartItems) {
            String[] parts = item.split("\\|");
            if (parts.length != 3) return "ERROR";

            String itemName = parts[0];
            if (itemName.isEmpty()) return "ERROR";

            long unitPrice;
            int quantity;
            try {
                unitPrice = Long.parseLong(parts[1]);
                quantity = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return "ERROR";
            }

            if (unitPrice < 0 || quantity <= 0) return "ERROR";
            subtotal += unitPrice * quantity;
        }

        // Ensure customer exists
        customers.computeIfAbsent(customerId, Customer::new);

        // Create bill (AtomicInteger for thread-safe sequential ID)
        int id = billCounter.incrementAndGet();
        String billId = "B" + id;
        Bill bill = new Bill(billId, customerId, subtotal);
        bills.put(billId, bill);

        return billId;
    }

    // ═══════════════════════════════════════════════
    // 2. Apply Discount
    // ═══════════════════════════════════════════════

    public long applyDiscount(String billId, String discountCode) {
        Bill bill = bills.get(billId);
        if (bill == null) return -1;

        bill.lock.lock();
        try {
            if (bill.paid) return -1;

            // Unknown code → no change, return current payable
            if (VALID_CODES.contains(discountCode)) {
                bill.appliedDiscounts.add(discountCode); // idempotent (Set)
            }

            return computePayable(bill);
        } finally {
            bill.lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // 3. Pay Bill
    // ═══════════════════════════════════════════════

    public String payBill(String billId, long amountPaid) {
        Bill bill = bills.get(billId);
        if (bill == null) return "ERROR";

        Customer customer = customers.get(bill.customerId);

        // Lock ordering: bill first, then customer (bill belongs to one customer → no deadlock)
        bill.lock.lock();
        try {
            if (bill.paid) return "ERROR";

            long payable = computePayable(bill);
            if (amountPaid != payable) return "ERROR";

            // Lock customer for points deduction + earning
            customer.lock.lock();
            try {
                // Mark paid
                bill.paid = true;

                // Deduct redeemed points (if REDEEM was applied)
                if (bill.appliedDiscounts.contains("REDEEM")) {
                    long redeemAmount = computeRedeemAmount(bill, customer);
                    customer.points -= redeemAmount;
                }

                // Earn points
                long pointsEarned = payable / 100;
                customer.points += pointsEarned;

                return "PAID|final=" + payable
                     + "|pointsEarned=" + pointsEarned
                     + "|totalPoints=" + customer.points
                     + "|level=" + customer.getLevel();
            } finally {
                customer.lock.unlock();
            }
        } finally {
            bill.lock.unlock();
        }
    }

    // ═══════════════════════════════════════════════
    // Discount Computation (deterministic, integer math)
    // ═══════════════════════════════════════════════

    /**
     * Compute payable amount for a bill given its applied discounts.
     *
     * Order:
     *   1. Start with subtotal
     *   2. Apply effective percentage (max of P10=10%, P20=20%)
     *   3. Apply FLAT100 (only if subtotal >= 500)
     *   4. Apply REDEEM (capped at 20% of current payable)
     *   5. Floor to 0 minimum
     */
    private long computePayable(Bill bill) {
        long payable = bill.subtotal;
        Customer customer = customers.get(bill.customerId);

        // Step 1: Effective percentage discount
        int effectivePercent = 0;
        if (bill.appliedDiscounts.contains("P20")) effectivePercent = 20;
        else if (bill.appliedDiscounts.contains("P10")) effectivePercent = 10;

        if (effectivePercent > 0) {
            long percentDiscount = (bill.subtotal * effectivePercent) / 100; // floor
            payable -= percentDiscount;
        }

        // Step 2: FLAT100
        if (bill.appliedDiscounts.contains("FLAT100") && bill.subtotal >= 500) {
            payable -= 100;
        }

        // Step 3: REDEEM
        if (bill.appliedDiscounts.contains("REDEEM") && customer != null) {
            long redeemAmount = computeRedeemAmountFromPayable(payable, customer.points);
            payable -= redeemAmount;
        }

        // Non-negative
        return Math.max(0, payable);
    }

    /**
     * Compute redeem amount: min(customer points, 20% of current payable)
     */
    private long computeRedeemAmount(Bill bill, Customer customer) {
        // Recompute payable up to the point before REDEEM
        long payable = bill.subtotal;

        int effectivePercent = 0;
        if (bill.appliedDiscounts.contains("P20")) effectivePercent = 20;
        else if (bill.appliedDiscounts.contains("P10")) effectivePercent = 10;

        if (effectivePercent > 0) {
            payable -= (bill.subtotal * effectivePercent) / 100;
        }

        if (bill.appliedDiscounts.contains("FLAT100") && bill.subtotal >= 500) {
            payable -= 100;
        }

        payable = Math.max(0, payable);
        return computeRedeemAmountFromPayable(payable, customer.points);
    }

    private long computeRedeemAmountFromPayable(long currentPayable, long customerPoints) {
        long redeemCap = (currentPayable * 20) / 100; // floor
        return Math.min(customerPoints, redeemCap);
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        BillingSystem system = new BillingSystem();

        // ─── Example 1: Basic bill + P10 + payment ───
        System.out.println("═══ Example 1 ═══\n");

        String b1 = system.createBill("C1", Arrays.asList("book|200|1", "pen|10|5"));
        System.out.println("createBill: " + b1);  // B1

        System.out.println("applyDiscount P10: " + system.applyDiscount("B1", "P10"));       // 225
        System.out.println("applyDiscount FLAT100: " + system.applyDiscount("B1", "FLAT100")); // 225 (subtotal < 500)

        System.out.println("payBill: " + system.payBill("B1", 225));
        // PAID|final=225|pointsEarned=2|totalPoints=2|level=BRONZE

        // ─── Example 2: Multiple discounts + REDEEM ───
        System.out.println("\n═══ Example 2 ═══\n");

        String b2 = system.createBill("C1", Arrays.asList("shoes|600|1", "tshirt|200|2"));
        System.out.println("createBill: " + b2);  // B2

        System.out.println("applyDiscount P20: " + system.applyDiscount("B2", "P20"));       // 800
        System.out.println("applyDiscount FLAT100: " + system.applyDiscount("B2", "FLAT100")); // 700
        System.out.println("applyDiscount REDEEM: " + system.applyDiscount("B2", "REDEEM"));   // 698

        System.out.println("payBill: " + system.payBill("B2", 698));
        // PAID|final=698|pointsEarned=6|totalPoints=6|level=BRONZE

        // ─── Example 3: Invalid payment ───
        System.out.println("\n═══ Example 3 ═══\n");

        String b3 = system.createBill("C2", Arrays.asList("mouse|499|1"));
        System.out.println("createBill: " + b3);  // B3

        System.out.println("applyDiscount P10: " + system.applyDiscount("B3", "P10"));  // 450
        System.out.println("payBill wrong amount: " + system.payBill("B3", 449));        // ERROR
        System.out.println("payBill correct: " + system.payBill("B3", 450));
        // PAID|final=450|pointsEarned=4|totalPoints=4|level=BRONZE

        // ─── Edge cases ───
        System.out.println("\n═══ Edge Cases ═══\n");

        System.out.println("Pay already paid: " + system.payBill("B1", 225));             // ERROR
        System.out.println("Discount on paid bill: " + system.applyDiscount("B1", "P20")); // -1
        System.out.println("Invalid bill: " + system.applyDiscount("B999", "P10"));        // -1

        // Idempotent discount
        String b4 = system.createBill("C1", Arrays.asList("laptop|1000|1"));
        System.out.println("\ncreateBill: " + b4);
        System.out.println("applyDiscount P10: " + system.applyDiscount(b4, "P10"));    // 900
        System.out.println("applyDiscount P10 again: " + system.applyDiscount(b4, "P10")); // 900 (idempotent)
        System.out.println("applyDiscount P20 (overrides P10): " + system.applyDiscount(b4, "P20")); // 800
    }
}
