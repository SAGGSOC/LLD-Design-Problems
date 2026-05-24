package hotel.model;

import hotel.enums.RoomType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Hotel {
    private final String hotelId;
    private final String name;
    private final String address;
    private final double starRating;
    private final List<Room> rooms;
    private final Map<String, Room> roomsById;

    public Hotel(String hotelId, String name, String address,
                 double starRating, List<Room> rooms) {
        this.hotelId = hotelId;
        this.name = name;
        this.address = address;
        this.starRating = starRating;
        this.rooms = rooms;
        this.roomsById = new ConcurrentHashMap<>();
        for (Room room : rooms) {
            roomsById.put(room.getRoomId(), room);
        }
    }

    public Room getRoom(String roomId) { return roomsById.get(roomId); }

    public List<Room> getRoomsByType(RoomType type) {
        return rooms.stream()
            .filter(room -> room.getType() == type)
            .collect(Collectors.toList());
    }

    public String getHotelId()    { return hotelId; }
    public String getName()       { return name; }
    public String getAddress()    { return address; }
    public double getStarRating() { return starRating; }
    public List<Room> getRooms()  { return rooms; }
}
