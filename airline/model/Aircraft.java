package airline.model;

import airline.enums.FareClass;

import java.util.Map;

public class Aircraft {
    private final String aircraftId;
    private final String model;            // "Boeing 787-9", "Airbus A320"
    private final Map<FareClass, Integer> seatCountByClass;

    public Aircraft(String aircraftId, String model,
                    Map<FareClass, Integer> seatCountByClass) {
        this.aircraftId = aircraftId;
        this.model = model;
        this.seatCountByClass = seatCountByClass;
    }

    public String getAircraftId()                          { return aircraftId; }
    public String getModel()                               { return model; }
    public Map<FareClass, Integer> getSeatCountByClass()   { return seatCountByClass; }
}
