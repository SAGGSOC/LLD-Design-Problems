import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

enum Direction {
    IDLE, UP, DOWN
}

/**
 * Elevator car — thread-safe.
 *
 * Uses ConcurrentHashMap-backed Set for requests so multiple threads
 * (controller thread + request submission threads) can access safely.
 *
 * The step() method is called from the controller's own thread.
 * addRequest() can be called from any thread (dispatcher, internal panel).
 */
public class Elevator {
    private volatile int currentFloor;
    private volatile Direction direction;
    private final Set<Request> requests;
    private final int id;
    private final int maxFloor;

    public Elevator(int id, int maxFloor) {
        this.id = id;
        this.maxFloor = maxFloor;
        this.currentFloor = 0;
        this.direction = Direction.IDLE;
        this.requests = ConcurrentHashMap.newKeySet(); // thread-safe Set
    }

    public int getId() { return id; }
    public int getCurrentFloor() { return currentFloor; }
    public Direction getDirection() { return direction; }
    public int getRequestCount() { return requests.size(); }

    /**
     * Thread-safe: can be called from any thread.
     */
    public boolean addRequest(Request request) {
        if (request.getFloor() < 0 || request.getFloor() > maxFloor) return false;
        if (request.getFloor() == currentFloor) return true; // already here
        return requests.add(request); // ConcurrentHashMap.newKeySet() is thread-safe
    }

    /**
     * Internal panel: passenger presses a floor button inside the elevator.
     * Called after the person enters the elevator.
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

        // Serve requests at current floor (pickup or destination)
        serveCurrentFloor();

        // If no more requests ahead, reverse direction
        if (!hasRequestsAhead()) {
            direction = (direction == Direction.UP) ? Direction.DOWN : Direction.UP;
            // Check again after reversing — might have requests now
            if (!hasRequestsAhead() && requests.isEmpty()) {
                direction = Direction.IDLE;
            }
            return;
        }

        // Move one floor
        if (direction == Direction.UP) {
            currentFloor++;
            System.out.println("  [Elevator " + id + "] Floor " + currentFloor + " ▲");
        } else if (direction == Direction.DOWN) {
            currentFloor--;
            System.out.println("  [Elevator " + id + "] Floor " + currentFloor + " ▼");
        }
    }

    private void serveCurrentFloor() {
        boolean served = requests.removeIf(r -> r.getFloor() == currentFloor);
        if (served) {
            System.out.println("  [Elevator " + id + "] ★ Doors open at floor " + currentFloor);
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
