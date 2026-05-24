package splitwise.strategy;

import splitwise.model.Split;
import splitwise.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * Each participant owes an exact amount. Inputs must sum to totalAmount.
 */
public class ExactSplitStrategy implements SplitStrategy {

    private static final double EPSILON = 0.01;

    @Override
    public List<Split> computeSplits(double totalAmount, List<User> participants,
                                     List<Double> inputs) {
        if (participants.size() != inputs.size()) {
            throw new IllegalArgumentException(
                "Exact split requires one amount per participant");
        }

        double sum = inputs.stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - totalAmount) > EPSILON) {
            throw new IllegalArgumentException(
                "Split amounts sum to " + sum + " but total is " + totalAmount);
        }

        List<Split> splits = new ArrayList<>();
        for (int i = 0; i < participants.size(); i++) {
            splits.add(new Split(participants.get(i), inputs.get(i)));
        }
        return splits;
    }
}
