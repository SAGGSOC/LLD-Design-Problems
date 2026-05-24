package cricinfo.strategy;

public class TestRules implements MatchFormatRules {
    @Override public int getMaxOversPerInnings() { return -1; }  // unlimited
    @Override public int getMaxInnings()         { return 4; }
    @Override public int getInningsPerTeam()     { return 2; }
}
