package parkinglot.exception;

public class VehicleAlreadyParkedException extends RuntimeException {
    public VehicleAlreadyParkedException(String licensePlate) {
        super("Vehicle already parked: " + licensePlate);
    }
}
