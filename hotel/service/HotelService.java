package hotel.service;

import hotel.enums.RoomType;
import hotel.model.DateRange;
import hotel.model.Hotel;
import hotel.model.Room;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class HotelService {
    private final Map<String, Hotel> hotelsById = new ConcurrentHashMap<>();
    private final AvailabilityIndex availabilityIndex;

    public HotelService(AvailabilityIndex availabilityIndex) {
        this.availabilityIndex = availabilityIndex;
    }

    public void addHotel(Hotel hotel) {
        hotelsById.put(hotel.getHotelId(), hotel);
    }

    public Hotel getHotel(String hotelId) {
        Hotel hotel = hotelsById.get(hotelId);
        if (hotel == null) throw new IllegalArgumentException("Hotel not found: " + hotelId);
        return hotel;
    }

    /**
     * Search for rooms in a hotel that are available for the given date range.
     * Filters by type, checks availability index for overlapping bookings.
     */
    public List<Room> searchAvailableRooms(String hotelId, RoomType type, DateRange range) {
        Hotel hotel = getHotel(hotelId);
        return hotel.getRoomsByType(type).stream()
            .filter(Room::isBookable)
            .filter(room -> availabilityIndex.isAvailable(room.getRoomId(), range))
            .collect(Collectors.toList());
    }

    public int getAvailableRoomCount(String hotelId, RoomType type, DateRange range) {
        return searchAvailableRooms(hotelId, type, range).size();
    }
}
