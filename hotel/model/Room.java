package hotel.model;

import hotel.enums.RoomStatus;
import hotel.enums.RoomType;

public class Room {
    private final String roomId;
    private final String roomNumber;
    private final int floor;
    private final RoomType type;
    private final int maxOccupancy;
    private final double baseRatePerNight;
    private RoomStatus status;

    public Room(String roomId, String roomNumber, int floor, RoomType type,
                int maxOccupancy, double baseRatePerNight) {
        this.roomId = roomId;
        this.roomNumber = roomNumber;
        this.floor = floor;
        this.type = type;
        this.maxOccupancy = maxOccupancy;
        this.baseRatePerNight = baseRatePerNight;
        this.status = RoomStatus.AVAILABLE;
    }

    public synchronized void markOccupied()    { this.status = RoomStatus.OCCUPIED; }
    public synchronized void markAvailable()   { this.status = RoomStatus.AVAILABLE; }
    public synchronized void markMaintenance() { this.status = RoomStatus.MAINTENANCE; }

    public boolean isBookable() {
        return status != RoomStatus.MAINTENANCE;
    }

    public String getRoomId()            { return roomId; }
    public String getRoomNumber()        { return roomNumber; }
    public int getFloor()                { return floor; }
    public RoomType getType()            { return type; }
    public int getMaxOccupancy()         { return maxOccupancy; }
    public double getBaseRatePerNight()  { return baseRatePerNight; }
    public RoomStatus getStatus()        { return status; }
}
