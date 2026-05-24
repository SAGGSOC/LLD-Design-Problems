package hotel.strategy;

import hotel.enums.RoomType;
import hotel.model.DateRange;
import hotel.model.Room;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pick the lowest-floor room of the requested type.
 * Alternative strategies could be: highest floor, closest to elevator, etc.
 */
public class LowestFloorFirstStrategy implements RoomAssignmentStrategy {

    @Override
    public Optional<Room> pickRoom(List<Room> candidateRooms, RoomType requestedType,
                                    DateRange dateRange) {
        return candidateRooms.stream()
            .filter(room -> room.getType() == requestedType)
            .min(Comparator.comparingInt(Room::getFloor)
                            .thenComparing(Room::getRoomNumber));
    }
}
