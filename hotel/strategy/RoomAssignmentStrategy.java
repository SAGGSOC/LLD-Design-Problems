package hotel.strategy;

import hotel.enums.RoomType;
import hotel.model.DateRange;
import hotel.model.Room;

import java.util.List;
import java.util.Optional;

public interface RoomAssignmentStrategy {
    Optional<Room> pickRoom(List<Room> candidateRooms, RoomType requestedType,
                             DateRange dateRange);
}
