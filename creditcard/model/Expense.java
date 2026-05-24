package creditcard.model;

import java.util.Map;

/**
 * Represents a single expense on the corporate credit card.
 * Wraps the raw Map<String, String> input for type-safe access.
 */
public class Expense {
    private final Map<String, String> data;

    public Expense(Map<String, String> data) {
        this.data = data;
    }

    public String getExpenseId() { return data.getOrDefault("expense_id", ""); }
    public String getTripId() { return data.getOrDefault("trip_id", ""); }
    public double getAmountUsd() {
        return Double.parseDouble(data.getOrDefault("amount_usd", "0"));
    }
    public String getExpenseType() { return data.getOrDefault("expense_type", ""); }
    public String getVendorType() { return data.getOrDefault("vendor_type", ""); }
    public String getVendorName() { return data.getOrDefault("vendor_name", ""); }

    public Map<String, String> getRawData() { return data; }

    @Override
    public String toString() {
        return String.format("Expense{id=%s, trip=%s, $%.2f, type=%s, vendor=%s(%s)}",
            getExpenseId(), getTripId(), getAmountUsd(), getExpenseType(), getVendorName(), getVendorType());
    }
}
