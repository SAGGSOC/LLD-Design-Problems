package deliverycost.model;

import deliverycost.enums.DriverStatus;
import java.util.ArrayList;
import java.util.List;

public class Driver {
    private final String driverId;
    private final String name;
    private DriverStatus status;
    private final List<Delivery> deliveries;

    public Driver(String driverId, String name) {
        this.driverId = driverId;
        this.name = name;
        this.status = DriverStatus.AVAILABLE;
        this.deliveries = new ArrayList<>();
    }

    public String getDriverId() { return driverId; }
    public String getName() { return name; }
    public DriverStatus getStatus() { return status; }
    public List<Delivery> getDeliveries() { return deliveries; }

    public void setStatus(DriverStatus status) { this.status = status; }

    public void addDelivery(Delivery delivery) {
        this.deliveries.add(delivery);
    }
}
