package splitwise.strategy;

import splitwise.model.Split;
import splitwise.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * Each participant owes a percentage of the total. Percentages must sum to 100.
 */
public class PercentageSplitStrategy implements SplitStrategy {

    private static final double EPSILON = 0.01;

    @Override
    public List<Split> computeSplits(double totalAmount, List<User> participants,
                                     List<Double> percentages) {
        if (participants.size() != percentages.size()) {
            throw new IllegalArgumentException(
                "Percentage split requires one percentage per participant");
        }

        double sum = percentages.stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 100.0) > EPSILON) {
            throw new IllegalArgumentException(
                "Percentages sum to " + sum + " but must sum to 100");
        }

        List<Split> splits = new ArrayList<>();
        double runningTotal = 0;
        // Compute for first N-1, give the last one the remainder (handles rounding)
        for (int i = 0; i < participants.size() - 1; i++) {
            double amount = Math.round(totalAmount * percentages.get(i)) / 100.0;
            runningTotal += amount;
            splits.add(new Split(participants.get(i), amount));
        }
        splits.add(new Split(
            participants.get(participants.size() - 1),
            Math.round((totalAmount - runningTotal) * 100) / 100.0));
        return splits;
    }
}
