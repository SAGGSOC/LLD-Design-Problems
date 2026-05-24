package splitwise.strategy;

import splitwise.enums.SplitType;

public class SplitStrategyFactory {

    public static SplitStrategy getStrategy(SplitType splitType) {
        switch (splitType) {
            case EQUAL:      return new EqualSplitStrategy();
            case EXACT:      return new ExactSplitStrategy();
            case PERCENTAGE: return new PercentageSplitStrategy();
            default: throw new IllegalArgumentException("Unknown split type: " + splitType);
        }
    }
}
