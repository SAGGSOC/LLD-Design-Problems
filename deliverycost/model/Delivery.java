package deliverycost.model;

import deliverycost.enums.DeliveryStatus;

public class Delivery {
    private final String deliveryId;
    private final String driverId;
    private final int startTime; // in minutes
    private final int endTime;   // in minutes
    private DeliveryStatus status;

    public Delivery(String deliveryId, String driverId, int startTime, int endTime) {
        this.deliveryId = deliveryId;
        this.driverId = driverId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = DeliveryStatus.COMPLETED;
    }

    public String getDeliveryId() { return deliveryId; }
    public String getDriverId() { return driverId; }
    public int getStartTime() { return startTime; }
    public int getEndTime() { return endTime; }
    public DeliveryStatus getStatus() { return status; }

    public void setStatus(DeliveryStatus status) { this.status = status; }

    public double getDurationInHours() {
        return (endTime - startTime) / 60.0;
    }
}
