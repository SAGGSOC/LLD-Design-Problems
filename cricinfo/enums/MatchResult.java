package cricinfo.enums;

public enum MatchResult {
    TEAM_A_WINS,
    TEAM_B_WINS,
    TIE,          // scores equal after all innings
    DRAW,         // test match ran out of time with no winner
    NO_RESULT     // abandoned
}
