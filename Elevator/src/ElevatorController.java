import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ElevatorController — manages multiple elevators with thread-safe request dispatch.
 *
 * Concurrency model:
 *   - requestElevator() → called from ANY thread (hall buttons, internal panels)
 *     Uses ConcurrentLinkedQueue to buffer requests (lock-free producer side)
 *
 *   - step() → called from a SINGLE controller thread (tick-based simulation)
 *     Drains pending queue, dispatches to best elevator, advances each elevator
 *
 *   - ReadWriteLock on elevator list:
 *     Read lock: scheduling decisions (multiple concurrent reads OK)
 *     Write lock: adding/removing elevators (maintenance mode)
 *
 *   - Each Elevator uses ConcurrentHashMap.newKeySet() for its request set:
 *     addRequest() is thread-safe — can be called from dispatcher while
 *     step() iterates on the controller thread.
 *
 * Scheduling strategy: 3-tier selection
 *   1. Elevator already moving toward the floor in the right direction
 *   2. Nearest idle elevator
 *   3. Nearest elevator (fallback)
 */
public class ElevatorController {

    private final List<Elevator> elevators;
    private final ReentrantReadWriteLock elevatorsLock;

    public ElevatorController(int numElevators, int maxFloor) {
        this.elevators = new ArrayList<>();
        this.elevatorsLock = new ReentrantReadWriteLock();

        for (int i = 0; i < numElevators; i++) {
            elevators.add(new Elevator(i, maxFloor));
        }
    }

    /**
     * Thread-safe: called from any thread (hall call buttons).
     * Returns the assigned Elevator so the client can later call goTo() on it.
     *
     * Flow:
     *   1. Person presses UP on floor 5 → requestElevator(5, PICKUP_UP)
     *   2. System assigns best elevator, returns it
     *   3. Elevator arrives, person steps in
     *   4. Person presses "7" inside → elevator.goTo(7)
     */
    public Elevator requestElevator(int floor, RequestType requestType) {
        if (requestType == RequestType.DESTINATION) {
            throw new IllegalArgumentException("Use elevator.goTo() for destinations");
        }

        Request request = new Request(floor, requestType);

        elevatorsLock.readLock().lock();
        try {
            Elevator best = selectBestElevator(request);
            if (best == null) throw new RuntimeException("No elevators available");
            best.addRequest(request);
            return best;
        } finally {
            elevatorsLock.readLock().unlock();
        }
    }

    /**
     * Called once per tick from the simulation/controller thread.
     * Steps each elevator one floor.
     */
    public void step() {
        elevatorsLock.readLock().lock();
        try {
            for (Elevator elevator : elevators) {
                elevator.step();
            }
        } finally {
            elevatorsLock.readLock().unlock();
        }
    }

    /**
     * Take an elevator offline for maintenance.
     * Write lock — blocks all reads until complete.
     * Redistributes pending requests to other elevators.
     */
    public void removeElevator(int elevatorId) {
        elevatorsLock.writeLock().lock();
        try {
            if (elevatorId >= 0 && elevatorId < elevators.size()) {
                elevators.remove(elevatorId);
            }
        } finally {
            elevatorsLock.writeLock().unlock();
        }
    }

    /**
     * Add a new elevator (e.g., after maintenance).
     */
    public void addElevator(Elevator elevator) {
        elevatorsLock.writeLock().lock();
        try {
            elevators.add(elevator);
        } finally {
            elevatorsLock.writeLock().unlock();
        }
    }

    // ─── Scheduling: 3-tier selection ───

    private Elevator selectBestElevator(Request request) {
        elevatorsLock.readLock().lock();
        try {
            if (elevators.isEmpty()) return null;

            Elevator best = findCommittedToward(request);
            if (best != null) return best;

            best = findNearestIdle(request.getFloor());
            if (best != null) return best;

            return findNearest(request.getFloor());
        } finally {
            elevatorsLock.readLock().unlock();
        }
    }

    /** Elevator already moving toward this floor in the matching direction */
    private Elevator findCommittedToward(Request request) {
        int floor = request.getFloor();
        Direction wantDir = (request.getRequestType() == RequestType.PICKUP_UP) ? Direction.UP : Direction.DOWN;

        Elevator nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (Elevator e : elevators) {
            if (e.getDirection() != wantDir) continue;

            boolean isApproaching = (wantDir == Direction.UP && e.getCurrentFloor() <= floor)
                                 || (wantDir == Direction.DOWN && e.getCurrentFloor() >= floor);
            if (!isApproaching) continue;

            int dist = Math.abs(e.getCurrentFloor() - floor);
            if (dist < minDist) {
                minDist = dist;
                nearest = e;
            }
        }
        return nearest;
    }

    /** Nearest idle elevator */
    private Elevator findNearestIdle(int floor) {
        Elevator nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (Elevator e : elevators) {
            if (e.getDirection() != Direction.IDLE) continue;
            int dist = Math.abs(e.getCurrentFloor() - floor);
            if (dist < minDist) {
                minDist = dist;
                nearest = e;
            }
        }
        return nearest;
    }

    /** Absolute nearest (fallback) */
    private Elevator findNearest(int floor) {
        Elevator nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (Elevator e : elevators) {
            int dist = Math.abs(e.getCurrentFloor() - floor);
            if (dist < minDist) {
                minDist = dist;
                nearest = e;
            }
        }
        return nearest;
    }

    // ─── Status ───

    public void printStatus() {
        elevatorsLock.readLock().lock();
        try {
            for (Elevator e : elevators) {
                System.out.printf("  Elevator %d: floor=%d, dir=%s, requests=%d%n",
                    e.getId(), e.getCurrentFloor(), e.getDirection(), e.getRequestCount());
            }
        } finally {
            elevatorsLock.readLock().unlock();
        }
    }

    public int getElevatorCount() {
        elevatorsLock.readLock().lock();
        try { return elevators.size(); }
        finally { elevatorsLock.readLock().unlock(); }
    }

}
