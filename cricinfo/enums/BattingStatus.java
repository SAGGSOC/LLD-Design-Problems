package cricinfo.enums;

public enum BattingStatus {
    YET_TO_BAT,
    AT_CREASE,      // currently batting (striker or non-striker)
    OUT,            // dismissed
    RETIRED,        // retired hurt or not out
    NOT_OUT         // innings ended while batting
}
