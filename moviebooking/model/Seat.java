package moviebooking.model;

import moviebooking.enums.SeatType;

public class Seat {
    private final String seatId;    // e.g. "SCR-1-A-15"
    private final String row;       // "A", "B", ...
    private final int number;       // 1, 2, 3, ...
    private final SeatType type;
    private final double basePrice;

    public Seat(String seatId, String row, int number, SeatType type, double basePrice) {
        this.seatId = seatId;
        this.row = row;
        this.number = number;
        this.type = type;
        this.basePrice = basePrice;
    }

    public String getSeatId()    { return seatId; }
    public String getRow()       { return row; }
    public int getNumber()       { return number; }
    public SeatType getType()    { return type; }
    public double getBasePrice() { return basePrice; }

    @Override
    public String toString() { return row + "-" + number; }
}
