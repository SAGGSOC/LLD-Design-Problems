package splitwise.strategy;

import splitwise.model.Split;
import splitwise.model.User;

import java.util.List;

/**
 * Strategy for dividing an expense total among participants.
 * Different implementations: Equal, Exact, Percentage.
 */
public interface SplitStrategy {

    /**
     * @param totalAmount   the expense total
     * @param participants  the users to split across
     * @param inputs        implementation-specific:
     *                      - EQUAL: ignored
     *                      - EXACT: list of amounts, same order as participants
     *                      - PERCENTAGE: list of percentages, same order
     */
    List<Split> computeSplits(double totalAmount,
                              List<User> participants,
                              List<Double> inputs);
}
