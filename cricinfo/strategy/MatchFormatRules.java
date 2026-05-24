package cricinfo.strategy;

public interface MatchFormatRules {
    /** Overs per innings, or -1 for unlimited (Test). */
    int getMaxOversPerInnings();

    /** How many total innings in this format (2 for T20/ODI, 4 for TEST). */
    int getMaxInnings();

    /** How many innings per team (1 for T20/ODI, 2 for TEST). */
    int getInningsPerTeam();
}
