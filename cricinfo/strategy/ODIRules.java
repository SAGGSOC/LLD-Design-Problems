package cricinfo.strategy;

public class ODIRules implements MatchFormatRules {
    @Override public int getMaxOversPerInnings() { return 50; }
    @Override public int getMaxInnings()         { return 2; }
    @Override public int getInningsPerTeam()     { return 1; }
}
