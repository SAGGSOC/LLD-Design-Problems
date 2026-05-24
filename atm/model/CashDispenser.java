package atm.model;

import atm.enums.Denomination;
import atm.exception.AtmOutOfCashException;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds cash inventory by denomination. Dispenses using greedy algorithm:
 * always try largest denomination first.
 *
 * Greedy works for standard currency denominations (100, 50, 20, 10, 5)
 * because they form a "canonical" system. For arbitrary denominations,
 * use dynamic programming (coin change problem).
 */
public class CashDispenser {
    private final Map<Denomination, Integer> inventory = new EnumMap<>(Denomination.class);

    public CashDispenser() {
        for (Denomination d : Denomination.values()) {
            inventory.put(d, 0);
        }
    }

    public synchronized void loadCash(Denomination denomination, int count) {
        if (count < 0) throw new IllegalArgumentException("Count must be non-negative");
        inventory.merge(denomination, count, Integer::sum);
    }

    public synchronized int getTotalCash() {
        int total = 0;
        for (Map.Entry<Denomination, Integer> entry : inventory.entrySet()) {
            total += entry.getKey().getValue() * entry.getValue();
        }
        return total;
    }

    public synchronized boolean canDispense(int amount) {
        if (amount <= 0 || amount > getTotalCash()) return false;

        // Simulate greedy dispense to see if it's possible
        int remaining = amount;
        for (Denomination d : Denomination.values()) {  // largest first (enum order)
            int available = inventory.get(d);
            int needed = remaining / d.getValue();
            int used = Math.min(available, needed);
            remaining -= used * d.getValue();
            if (remaining == 0) return true;
        }
        return remaining == 0;
    }

    /**
     * Dispense the given amount using the fewest notes possible.
     * Returns a breakdown map: denomination → count.
     * Updates inventory atomically — either the full amount is dispensed or nothing.
     */
    public synchronized Map<Denomination, Integer> dispense(int amount) {
        if (!canDispense(amount)) {
            throw new AtmOutOfCashException(
                "Cannot dispense $" + amount + " with available inventory");
        }

        Map<Denomination, Integer> breakdown = new LinkedHashMap<>();
        int remaining = amount;

        for (Denomination d : Denomination.values()) {  // largest first
            int available = inventory.get(d);
            int needed = remaining / d.getValue();
            int used = Math.min(available, needed);
            if (used > 0) {
                breakdown.put(d, used);
                remaining -= used * d.getValue();
            }
        }

        // Commit the dispense — deduct from inventory
        for (Map.Entry<Denomination, Integer> entry : breakdown.entrySet()) {
            inventory.merge(entry.getKey(), -entry.getValue(), Integer::sum);
        }

        return breakdown;
    }

    public synchronized Map<Denomination, Integer> getInventory() {
        return new EnumMap<>(inventory);
    }
}
