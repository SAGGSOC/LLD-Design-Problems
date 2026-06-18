import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

enum Direction {
    IDLE, UP, DOWN
}

enum DoorState {
    OPEN, CLOSED
}

// ═══════════════════════════════════════════════
// Door — physical door component of the elevator car
// ═══════════════════════════════════════════════

class Door {
    private volatile DoorState state;
    private final int elevatorId;

    Door(int elevatorId) {
        this.elevatorId = elevatorId;
        this.state = DoorState.CLOSED;
    }

    public void open() {
        state = DoorState.OPEN;
    }

    public void close() {
        state = DoorState.CLOSED;
    }

    public DoorState getState() { return state; }
    public boolean isOpen() { return state == DoorState.OPEN; }
}

// ═══════════════════════════════════════════════
// ElevatorCar — physical car (wraps door + display)
// ═══════════════════════════════════════════════

class ElevatorCar {
    private final int id;
    private final Door door;
    private volatile int displayFloor; // what the display shows inside the car

    ElevatorCar(int id) {
        this.id = id;
        this.door = new Door(id);
        this.displayFloor = 0;
    }

    public int getId() { return id; }
    public Door getDoor() { return door; }

    public void updateDisplay(int floor, Direction direction) {
        this.displayFloor = floor;
    }

    public int getDisplayFloor() { return displayFloor; }

    /** Open door when serving a floor */
    public void openDoorAtFloor(int floor) {
        door.open();
        System.out.println("  [Elevator " + id + "] ★ Doors OPEN at floor " + floor);
    }

    /** Close door before moving */
    public void closeDoor() {
        if (door.isOpen()) {
            door.close();
        }
    }

    /** Move one floor (just updates display; actual floor tracking is in Elevator) */
    public void showMovement(int floor, Direction direction) {
        updateDisplay(floor, direction);
        String arrow = (direction == Direction.UP) ? "▲" : "▼";
        System.out.println("  [Elevator " + id + "] Floor " + floor + " " + arrow);
    }
}

// ═══════════════════════════════════════════════
// Elevator — controller logic wrapping the physical car
// ═══════════════════════════════════════════════

/**
 * Elevator — thread-safe.
 *
 * Composes ElevatorCar (physical) + request management (logical).
 * step() drives SCAN algorithm and delegates to car for door/display.
 */
public class Elevator {
    private volatile int currentFloor;
    private volatile Direction direction;
    private final Set<Request> requests;
    private final int id;
    private final int maxFloor;
    private final ElevatorCar car;

    public Elevator(int id, int maxFloor) {
        this.id = id;
        this.maxFloor = maxFloor;
        this.currentFloor = 0;
        this.direction = Direction.IDLE;
        this.requests = ConcurrentHashMap.newKeySet();
        this.car = new ElevatorCar(id);
    }

    public int getId() { return id; }
    public int getCurrentFloor() { return currentFloor; }
    public Direction getDirection() { return direction; }
    public int getRequestCount() { return requests.size(); }
    public ElevatorCar getCar() { return car; }
    public DoorState getDoorState() { return car.getDoor().getState(); }

    /**
     * Thread-safe: can be called from any thread.
     */
    public boolean addRequest(Request request) {
        if (request.getFloor() < 0 || request.getFloor() > maxFloor) return false;
        if (request.getFloor() == currentFloor) return true;
        return requests.add(request);
    }

    /**
     * Internal panel: passenger presses a floor button inside the elevator.
     * Thread-safe.
     */
    public void goTo(int floor) {
        addRequest(new Request(floor, RequestType.DESTINATION));
    }

    /**
     * Called from the controller thread only.
     * Moves elevator one floor per step (SCAN algorithm).
     */
    public void step() {
        if (requests.isEmpty()) {
            direction = Direction.IDLE;
            return;
        }

        // If IDLE, pick direction toward nearest request
        if (direction == Direction.IDLE) {
            Request nearest = findNearest();
            if (nearest == null) return;
            direction = (nearest.getFloor() > currentFloor) ? Direction.UP : Direction.DOWN;
        }

        // Serve requests at current floor
        serveCurrentFloor();

        // If no more requests ahead, reverse direction
        if (!hasRequestsAhead()) {
            direction = (direction == Direction.UP) ? Direction.DOWN : Direction.UP;
            if (!hasRequestsAhead() && requests.isEmpty()) {
                direction = Direction.IDLE;
            }
            return;
        }

        // Close door before moving
        car.closeDoor();

        // Move one floor
        if (direction == Direction.UP) {
            currentFloor++;
        } else if (direction == Direction.DOWN) {
            currentFloor--;
        }
        car.showMovement(currentFloor, direction);
    }

    private void serveCurrentFloor() {
        boolean served = requests.removeIf(r -> r.getFloor() == currentFloor);
        if (served) {
            car.openDoorAtFloor(currentFloor);
        }
    }

    private boolean hasRequestsAhead() {
        for (Request r : requests) {
            if (direction == Direction.UP && r.getFloor() > currentFloor) return true;
            if (direction == Direction.DOWN && r.getFloor() < currentFloor) return true;
        }
        return false;
    }

    private Request findNearest() {
        Request nearest = null;
        int minDist = Integer.MAX_VALUE;
        for (Request r : requests) {
            int dist = Math.abs(r.getFloor() - currentFloor);
            if (dist < minDist) {
                minDist = dist;
                nearest = r;
            }
        }
        return nearest;
    }
}
