package cricinfo.strategy;

public class T20Rules implements MatchFormatRules {
    @Override public int getMaxOversPerInnings() { return 20; }
    @Override public int getMaxInnings()         { return 2; }
    @Override public int getInningsPerTeam()     { return 1; }
}
