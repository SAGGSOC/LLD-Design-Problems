public enum ElevatorDirection{
    UP, DOWN, IDLE;
}
public enum DoorState{
    DOOR_OPEN, DOOR_CLOSE;
}
public class Door{
    private DoorState doorState;
    Door(){
        doorState = DoorState.DOOR_CLOSE;
    }
    public void openDoor(int id){
        doorState = DoorState.DOOR_OPEN;
        System.out.println("Opening the Elevator door of elevator:" + id);
    }
    public void closeDoor(int id){
        doorState = DoorState.DOOR_CLOSE;
        System.out.println("Closing the Elevator door of elevator:" + id);
    }
}public class Floor{
    int floorNum;
    ExternalButton upButton;
    ExternalButton downButton;
    public Floor(int floorNumber, ExternalDispatcher dispatcher) {
        this.floorNumber = floorNumber;
        this.upButton = new ExternalButton(dispatcher);
        this.downButton = new ExternalButton(dispatcher);
    }
    public void pressUpButton(){
        upButton.pressButton(floorNum, ElevatorDirection.UP);
    }
    public void pressDownButton() {
        downButton.pressButton(floorNumber, ElevatorDirection.DOWN);
    }
}
public class Building{
    List<Floor> floors = new ArrayList<>();
    public Building(int totalFloors,ExternalDispatcher dispatcher){
        for(int i=1;i<=totalFloors;i++){
            floors.add(new Floor(i, dispatcher));
        }
    }
    public Floor getFloor(int floor){
        floors.get(floor - 1);
    }
}
public class ElevatorCar{
    int id;
    int currentFloor;
    int nextFloorStoppage;
    ElevatorDirection movingDirection;
    Door door;

    public ElevatorCar(int id){
        this.id = id;
        currentFloor = 0;
        movingDirection = ElevatorDirection.IDLE;
        door = new Door();
    }
    public void showDisplay(){

    }

    public void moveElevator(int destinationFloor){
        this.nextFloorStoppage = destinationFloor;
        if(currentFloor == nextFloorStoppage){
            door.openDoor(id);
            return;
        }
        int startFloor = this.currentFloor;
        door.closeDoor(id);
        if(nextFloorStoppage >= currentFloor){
            movingDirection = ElevatorDirection.UP;
            showDisplay();
            for(int = start+1;i<= nextFloorStoppage; i++){
                try {
                    Thread.sleep(5);
                }catch (Exception e) {
                }
                setCurrentFloor(i);
                showDisplay();
            }
        } else {
            movingDirection = ElevatorDirection.DOWN;

            showDisplay();
            for (int i = startFloor-1; i>= nextFloorStoppage; i--) {
                try {
                    Thread.sleep(5);
                }catch (Exception e) {

                }
                setCurrentFloor(i);
                showDisplay();
            }
        }
        door.openDoor(id);
    }
    public void setCurrentFloor(int currentFloor) {
        this.currentFloor = currentFloor;
    }
}
public class ElevatorController implements Runnable{
    PriorityBlockingQueue<Integer> upMinPq;
    PriorityBlockingQueue<Integer> downMaxPq;
    ElevatorCar elevatorCar;
    private final Object monitor = new Object();

    ElevatorController(ElevatorCar elevatorCar) {
        this.elevatorCar = elevatorCar;
        upMinPQ = new PriorityBlockingQueue<>();
        downMaxPQ = new PriorityBlockingQueue<>(10, (a, b) -> b - a);
    }

    public void submitRequest(int destinationFloor) {
        enqueueRequest(destinationFloor);
    }

    private void enqueueRequest(int destinationFloor) {
        System.out.println("Request details-> destinationFloor: " + destinationFloor + " accepted by elevator:" + elevatorCar.id);

        if (destinationFloor == elevatorCar.nextFloorStoppage){
            return;
        }
        if (destinationFloor >= elevatorCar.nextFloorStoppage) {
            if (!upMinPQ.contains(destinationFloor)) {
                upMinPQ.offer(destinationFloor);
            }
        } else {
            if (!downMaxPQ.contains(destinationFloor)) {
                downMaxPQ.offer(destinationFloor);
            }
        }
        synchronized (monitor) {
            monitor.notify();   // wake elevator thread
        }
    }
    @Override
    public void run() {
        controlElevator();
    }
    public void controlElevator() {
        while (true) {

            //no request, go to sleep
            synchronized (monitor) {
                while (upMinPQ.isEmpty() && downMaxPQ.isEmpty()) {
                    try {
                        System.out.println("elevator:" + elevatorCar.id + " is IDLE");
                        elevatorCar.movingDirection = ElevatorDirection.IDLE;
                        monitor.wait(); // sleep until request arrives
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }


            while (!upMinPQ.isEmpty()) {
                int floor = upMinPQ.poll();
                System.out.println("Serving floor: " + floor + " by elevator:" + elevatorCar.id + " currentFloor: " + elevatorCar.currentFloor);
                elevatorCar.moveElevator(floor);
            }


            while (!downMaxPQ.isEmpty()) {
                int floor = downMaxPQ.poll();
                System.out.println("Serving floor: " + floor + " by elevator:" + elevatorCar.id + " currentFloor: " + elevatorCar.currentFloor);
                elevatorCar.moveElevator(floor);
            }
        }
    }
}
public class ElevatorScheduler{
    private final List<ElevatorController> controllers;

}
public class ExternalDispatcher{
    ElevatorScheduler scheduler;
}
public class ExternalButton{
    private final ExternalDispatcher dispatcher;
    public ExternalButton(ExternalDispatcher dispatcher){
        this.dispatcher = dispatcher;
    }
    public void pressButton(int floor, ElevatorDirection direction){
        dispatcher.submitExternalRequest(floor, direction);
    }
}

public class Building{
    List<Floor> floors = new ArrayList<>();
    public Building (int totalFloors,ExternalDispatcher dispatcher){
        for(int i=1;i<=totalFloors;i++){
            floors.add(new Floor(i, dispatcher));
        }
    }
}