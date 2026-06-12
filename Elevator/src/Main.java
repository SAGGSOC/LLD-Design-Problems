import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Elevator System Demo — demonstrates:
 *
 * 1. requestElevator(floor, type) → returns assigned Elevator
 * 2. elevator.goTo(floor) → passenger presses internal button
 * 3. Concurrent requests from multiple threads
 * 4. ReadWriteLock for elevator list (maintenance mode)
 * 5. ConcurrentHashMap.newKeySet() for thread-safe request sets
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {
        int numElevators = 3;
        int maxFloor = 9;

        ElevatorController controller = new ElevatorController(numElevators, maxFloor);

        // Tick-based: step() runs every 500ms on its own thread
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(controller::step, 0, 500, TimeUnit.MILLISECONDS);

        System.out.println("═══ Elevator System (Concurrent) ═══");
        System.out.println("Elevators: " + numElevators + ", Floors: 0-" + maxFloor);
        System.out.println();

        Thread.sleep(600);
        controller.printStatus();

        // ─── Real-world flow: request elevator, then press destination inside ───
        System.out.println("\n--- Person on floor 5 presses UP ---");
        Elevator myElevator = controller.requestElevator(5, RequestType.PICKUP_UP);
        System.out.println("Assigned: Elevator " + myElevator.getId());

        // Wait for elevator to arrive at floor 5
        Thread.sleep(3000);
        controller.printStatus();

        // Person steps in, presses floor 8
        System.out.println("\n--- Person inside presses floor 8 ---");
        myElevator.goTo(8);

        Thread.sleep(2500);
        controller.printStatus();

        // ─── Concurrent hall calls from different threads ───
        System.out.println("\n--- Concurrent hall calls ---");

        Thread t1 = new Thread(() -> {
            Elevator e = controller.requestElevator(2, RequestType.PICKUP_UP);
            System.out.println("[Thread-1] Floor 2 UP → Elevator " + e.getId());
            // Simulate: person enters, presses floor 6
            e.goTo(6);
        });

        Thread t2 = new Thread(() -> {
            Elevator e = controller.requestElevator(7, RequestType.PICKUP_DOWN);
            System.out.println("[Thread-2] Floor 7 DOWN → Elevator " + e.getId());
            e.goTo(1);
        });

        Thread t3 = new Thread(() -> {
            Elevator e = controller.requestElevator(4, RequestType.PICKUP_UP);
            System.out.println("[Thread-3] Floor 4 UP → Elevator " + e.getId());
            e.goTo(9);
        });

        t1.start(); t2.start(); t3.start();
        t1.join(); t2.join(); t3.join();

        Thread.sleep(5000);
        System.out.println("\n--- After all served ---");
        controller.printStatus();

        // ─── Maintenance: remove an elevator ───
        System.out.println("\n--- Remove Elevator 2 for maintenance ---");
        controller.removeElevator(2);
        System.out.println("Remaining: " + controller.getElevatorCount());
        controller.printStatus();

        // Shutdown
        ticker.shutdown();
        ticker.awaitTermination(1, TimeUnit.SECONDS);
        System.out.println("\nDone.");
    }
}
