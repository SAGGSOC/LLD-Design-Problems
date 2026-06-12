import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Elevator System — LLD Interview (Single File)
 *
 * Design:
 *   - SCAN algorithm (elevator serves all up requests going up, then all down requests going down)
 *   - PriorityBlockingQueue: minPQ for UP, maxPQ for DOWN
 *   - ElevatorController runs on its own thread, sleeps when idle, wakes on new request
 *   - ExternalDispatcher chooses which elevator to assign (scheduling strategy)
 *
 * Classes:
 *   ElevatorCar → physical car (door, floor, direction)
 *   ElevatorController → controls one car (request queues, SCAN logic)
 *   ElevatorScheduler → assigns requests to controllers (nearest car)
 *   ExternalDispatcher → receives button presses, delegates to scheduler
 *   Floor / Building → physical structure
 */
public class ElevatorSystem {

    // ═══════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════

    enum ElevatorDirection { UP, DOWN, IDLE }
    enum DoorState { OPEN, CLOSED }

    // ═══════════════════════════════════════════════
    // Door
    // ═══════════════════════════════════════════════

    static class Door {
        private DoorState state;

        Door() { state = DoorState.CLOSED; }

        public void open(int elevatorId) {
            state = DoorState.OPEN;
            System.out.println("  [Elevator " + elevatorId + "] Door OPEN");
        }

        public void close(int elevatorId) {
            state = DoorState.CLOSED;
            System.out.println("  [Elevator " + elevatorId + "] Door CLOSED");
        }
    }

    // ═══════════════════════════════════════════════
    // ElevatorCar
    // ═══════════════════════════════════════════════

    static class ElevatorCar {
        final int id;
        int currentFloor;
        ElevatorDirection direction;
        Door door;

        public ElevatorCar(int id) {
            this.id = id;
            this.currentFloor = 0;
            this.direction = ElevatorDirection.IDLE;
            this.door = new Door();
        }

        public void moveToFloor(int destinationFloor) {
            if (currentFloor == destinationFloor) {
                door.open(id);
                return;
            }

            door.close(id);

            if (destinationFloor > currentFloor) {
                direction = ElevatorDirection.UP;
                for (int i = currentFloor + 1; i <= destinationFloor; i++) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    currentFloor = i;
                    System.out.println("  [Elevator " + id + "] Floor " + currentFloor + " ▲");
                }
            } else {
                direction = ElevatorDirection.DOWN;
                for (int i = currentFloor - 1; i >= destinationFloor; i--) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    currentFloor = i;
                    System.out.println("  [Elevator " + id + "] Floor " + currentFloor + " ▼");
                }
            }

            door.open(id);
        }
    }

    // ═══════════════════════════════════════════════
    // ElevatorController (one per car, runs on its own thread)
    // SCAN algorithm: serve all UP, then all DOWN, repeat
    // ═══════════════════════════════════════════════

    static class ElevatorController implements Runnable {
        private final PriorityBlockingQueue<Integer> upMinPQ;     // min-heap: serve lowest floor first going up
        private final PriorityBlockingQueue<Integer> downMaxPQ;   // max-heap: serve highest floor first going down
        private final ElevatorCar car;
        private final Object monitor = new Object();

        ElevatorController(ElevatorCar car) {
            this.car = car;
            this.upMinPQ = new PriorityBlockingQueue<>();
            this.downMaxPQ = new PriorityBlockingQueue<>(10, (a, b) -> b - a); // reverse order
        }

        public void submitRequest(int destinationFloor) {
            System.out.println("  Request: floor " + destinationFloor + " → Elevator " + car.id);

            if (destinationFloor >= car.currentFloor) {
                if (!upMinPQ.contains(destinationFloor)) {
                    upMinPQ.offer(destinationFloor);
                }
            } else {
                if (!downMaxPQ.contains(destinationFloor)) {
                    downMaxPQ.offer(destinationFloor);
                }
            }

            synchronized (monitor) {
                monitor.notify(); // wake elevator thread
            }
        }

        @Override
        public void run() {
            while (true) {
                // Sleep when idle
                synchronized (monitor) {
                    while (upMinPQ.isEmpty() && downMaxPQ.isEmpty()) {
                        try {
                            car.direction = ElevatorDirection.IDLE;
                            System.out.println("  [Elevator " + car.id + "] IDLE at floor " + car.currentFloor);
                            monitor.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }

                // Serve all UP requests (ascending)
                while (!upMinPQ.isEmpty()) {
                    int floor = upMinPQ.poll();
                    System.out.println("  [Elevator " + car.id + "] Serving UP → floor " + floor);
                    car.moveToFloor(floor);
                }

                // Serve all DOWN requests (descending)
                while (!downMaxPQ.isEmpty()) {
                    int floor = downMaxPQ.poll();
                    System.out.println("  [Elevator " + car.id + "] Serving DOWN → floor " + floor);
                    car.moveToFloor(floor);
                }
            }
        }

        public int getCurrentFloor() { return car.currentFloor; }
        public ElevatorDirection getDirection() { return car.direction; }
        public int getId() { return car.id; }
    }

    // ═══════════════════════════════════════════════
    // ElevatorScheduler — assigns request to best elevator
    // Strategy: Nearest car that's idle or moving toward the floor
    // ═══════════════════════════════════════════════

    static class ElevatorScheduler {
        private final List<ElevatorController> controllers;

        public ElevatorScheduler(List<ElevatorController> controllers) {
            this.controllers = controllers;
        }

        public void assignRequest(int floor, ElevatorDirection direction) {
            ElevatorController best = findBestElevator(floor, direction);
            best.submitRequest(floor);
        }

        private ElevatorController findBestElevator(int floor, ElevatorDirection direction) {
            ElevatorController nearest = null;
            int minDistance = Integer.MAX_VALUE;

            for (ElevatorController ctrl : controllers) {
                int distance = Math.abs(ctrl.getCurrentFloor() - floor);

                // Prefer: idle elevators, or elevators moving toward the floor
                boolean isIdleOrMovingToward =
                    ctrl.getDirection() == ElevatorDirection.IDLE ||
                    (ctrl.getDirection() == ElevatorDirection.UP && direction == ElevatorDirection.UP && ctrl.getCurrentFloor() <= floor) ||
                    (ctrl.getDirection() == ElevatorDirection.DOWN && direction == ElevatorDirection.DOWN && ctrl.getCurrentFloor() >= floor);

                if (isIdleOrMovingToward && distance < minDistance) {
                    minDistance = distance;
                    nearest = ctrl;
                }
            }

            // Fallback: just pick nearest regardless of direction
            if (nearest == null) {
                for (ElevatorController ctrl : controllers) {
                    int distance = Math.abs(ctrl.getCurrentFloor() - floor);
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearest = ctrl;
                    }
                }
            }

            return nearest;
        }
    }

    // ═══════════════════════════════════════════════
    // ExternalDispatcher — receives button presses
    // ═══════════════════════════════════════════════

    static class ExternalDispatcher {
        private final ElevatorScheduler scheduler;

        public ExternalDispatcher(ElevatorScheduler scheduler) {
            this.scheduler = scheduler;
        }

        public void submitExternalRequest(int floor, ElevatorDirection direction) {
            scheduler.assignRequest(floor, direction);
        }
    }

    // ═══════════════════════════════════════════════
    // Floor & Building
    // ═══════════════════════════════════════════════

    static class Floor {
        private final int floorNum;
        private final ExternalDispatcher dispatcher;

        public Floor(int floorNum, ExternalDispatcher dispatcher) {
            this.floorNum = floorNum;
            this.dispatcher = dispatcher;
        }

        public void pressUpButton() {
            System.out.println("Floor " + floorNum + ": UP button pressed");
            dispatcher.submitExternalRequest(floorNum, ElevatorDirection.UP);
        }

        public void pressDownButton() {
            System.out.println("Floor " + floorNum + ": DOWN button pressed");
            dispatcher.submitExternalRequest(floorNum, ElevatorDirection.DOWN);
        }
    }

    static class Building {
        private final List<Floor> floors;

        public Building(int totalFloors, ExternalDispatcher dispatcher) {
            floors = new ArrayList<>();
            for (int i = 1; i <= totalFloors; i++) {
                floors.add(new Floor(i, dispatcher));
            }
        }

        public Floor getFloor(int floorNum) {
            return floors.get(floorNum - 1);
        }
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) throws InterruptedException {
        // Create 2 elevator cars
        ElevatorCar car1 = new ElevatorCar(1);
        ElevatorCar car2 = new ElevatorCar(2);

        // Create controllers
        ElevatorController ctrl1 = new ElevatorController(car1);
        ElevatorController ctrl2 = new ElevatorController(car2);

        List<ElevatorController> controllers = new ArrayList<>();
        controllers.add(ctrl1);
        controllers.add(ctrl2);

        // Create scheduler and dispatcher
        ElevatorScheduler scheduler = new ElevatorScheduler(controllers);
        ExternalDispatcher dispatcher = new ExternalDispatcher(scheduler);

        // Create building
        Building building = new Building(10, dispatcher);

        // Start elevator threads
        Thread t1 = new Thread(ctrl1, "Elevator-1");
        Thread t2 = new Thread(ctrl2, "Elevator-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        System.out.println("═══ Elevator System ═══\n");

        // Simulate requests
        Thread.sleep(100); // let elevators go idle

        building.getFloor(5).pressUpButton();    // Someone on floor 5 wants to go up
        building.getFloor(3).pressUpButton();    // Someone on floor 3 wants to go up
        building.getFloor(8).pressDownButton();  // Someone on floor 8 wants to go down

        Thread.sleep(1000); // let elevators process

        // Internal request: passenger inside elevator 1 presses floor 7
        System.out.println("\nPassenger in Elevator 1 presses floor 7");
        ctrl1.submitRequest(7);

        Thread.sleep(1000); // let it process

        // Another batch
        System.out.println("\n--- More requests ---");
        building.getFloor(1).pressUpButton();
        building.getFloor(10).pressDownButton();

        Thread.sleep(1500);
        System.out.println("\nDone.");
    }
}
