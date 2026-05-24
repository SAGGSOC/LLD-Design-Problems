package cricinfo.enums;

public enum ExtraType {
    WIDE,       // bowler bowls too wide — 1 run, ball not counted
    NO_BALL,    // bowler oversteps — 1 run, ball not counted, free hit next
    BYE,        // ball passes, batsmen run — ball counted, 0 to bowler
    LEG_BYE     // ball off pad, batsmen run — ball counted, 0 to bowler
}
