package cricinfo.strategy;

import cricinfo.enums.MatchFormat;

public class MatchFormatFactory {
    public static MatchFormatRules getRules(MatchFormat format) {
        switch (format) {
            case T20:  return new T20Rules();
            case ODI:  return new ODIRules();
            case TEST: return new TestRules();
            default:   throw new IllegalArgumentException("Unknown format: " + format);
        }
    }
}
