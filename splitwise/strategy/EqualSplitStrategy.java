package splitwise.strategy;

import splitwise.model.Split;
import splitwise.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * Divides the total equally among participants.
 * Handles rounding by giving the first participant any remainder cents.
 */
public class EqualSplitStrategy implements SplitStrategy {

    @Override
    public List<Split> computeSplits(double totalAmount, List<User> participants,
                                     List<Double> inputs) {
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("No participants");
        }

        int count = participants.size();
        // Work in cents for precision, then convert back
        long totalCents = Math.round(totalAmount * 100);
        long perPersonCents = totalCents / count;
        long remainder = totalCents - (perPersonCents * count);

        List<Split> splits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long cents = perPersonCents + (i < remainder ? 1 : 0);  // distribute leftover cents
            splits.add(new Split(participants.get(i), cents / 100.0));
        }
        return splits;
    }
}
