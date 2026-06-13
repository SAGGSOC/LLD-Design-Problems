/**
 * Elevator System Demo — no ScheduledExecutorService.
 * Just a simple loop calling step() with Thread.sleep().
 *
 * Flow:
 *   1. requestElevator(floor, type) → returns assigned Elevator
 *   2. elevator.goTo(floor) → internal button press
 *   3. step() advances all elevators one floor per call
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {
        int numElevators = 3;
        int maxFloor = 9;

        ElevatorController controller = new ElevatorController(numElevators, maxFloor);

        System.out.println("═══ Elevator System ═══");
        System.out.println("Elevators: " + numElevators + ", Floors: 0-" + maxFloor + "\n");

        // ─── Scenario 1: Person on floor 5 wants to go to floor 8 ───
        System.out.println("--- Person on floor 5 presses UP ---");
        Elevator e1 = controller.requestElevator(5, RequestType.PICKUP_UP);
        System.out.println("Assigned: Elevator " + e1.getId());

        // Step until elevator reaches floor 5
        stepUntilIdle(controller);
        controller.printStatus();

        // Person enters, presses 8
        System.out.println("\n--- Person presses floor 8 inside ---");
        e1.goTo(8);

        stepUntilIdle(controller);
        controller.printStatus();

        // ─── Scenario 2: Multiple requests ───
        System.out.println("\n--- Floor 2 UP, Floor 7 DOWN ---");
        Elevator e2 = controller.requestElevator(2, RequestType.PICKUP_UP);
        System.out.println("Floor 2 UP → Elevator " + e2.getId());
        e2.goTo(6); // will press 6 once inside

        Elevator e3 = controller.requestElevator(7, RequestType.PICKUP_DOWN);
        System.out.println("Floor 7 DOWN → Elevator " + e3.getId());
        e3.goTo(1); // will press 1 once inside

        stepUntilIdle(controller);
        controller.printStatus();

        // ─── Scenario 3: Same elevator gets multiple destinations ───
        System.out.println("\n--- Person on floor 0 going to 9 ---");
        Elevator e4 = controller.requestElevator(0, RequestType.PICKUP_UP);
        System.out.println("Floor 0 UP → Elevator " + e4.getId());
        e4.goTo(4);
        e4.goTo(7);
        e4.goTo(9); // multiple stops

        stepUntilIdle(controller);
        controller.printStatus();

        System.out.println("\nDone.");
    }

    /**
     * Keep calling step() until all elevators are IDLE (no pending requests).
     * Simple simulation loop — no threads needed.
     */
    private static void stepUntilIdle(ElevatorController controller) throws InterruptedException {
        int maxSteps = 50; // safety limit
        int steps = 0;

        while (steps < maxSteps) {
            controller.step();
            steps++;

            // Check if all idle
            if (controller.allIdle()) break;

            Thread.sleep(100); // simulate time passing
        }
    }
}
