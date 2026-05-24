package parkinglot.model;

import parkinglot.enums.SpotType;
import parkinglot.enums.VehicleType;
import parkinglot.exception.SpotOccupiedException;

public abstract class ParkingSpot {
    private final String spotId;
    private final int floorNumber;
    private final int spotNumber;
    private final SpotType spotType;
    private final boolean isHandicap;
    private final boolean hasEVCharging;
    private Vehicle vehicle;
    private boolean occupied;

    protected ParkingSpot(String spotId, int floorNumber, int spotNumber,
                          SpotType spotType, boolean isHandicap, boolean hasEVCharging) {
        this.spotId = spotId;
        this.floorNumber = floorNumber;
        this.spotNumber = spotNumber;
        this.spotType = spotType;
        this.isHandicap = isHandicap;
        this.hasEVCharging = hasEVCharging;
        this.occupied = false;
    }

    /** Each subclass defines which vehicle types it can accommodate. */
    public abstract boolean canFit(VehicleType vehicleType);

    public synchronized void assignVehicle(Vehicle incomingVehicle) {
        if (occupied) throw new SpotOccupiedException(spotId);
        this.vehicle = incomingVehicle;
        this.occupied = true;
    }

    public synchronized Vehicle removeVehicle() {
        Vehicle parkedVehicle = this.vehicle;
        this.vehicle = null;
        this.occupied = false;
        return parkedVehicle;
    }

    public boolean isAvailable()      { return !occupied; }
    public String getSpotId()         { return spotId; }
    public int getFloorNumber()       { return floorNumber; }
    public int getSpotNumber()        { return spotNumber; }
    public SpotType getSpotType()     { return spotType; }
    public Vehicle getVehicle()       { return vehicle; }
    public boolean isHandicap()       { return isHandicap; }
    public boolean hasEVCharging()    { return hasEVCharging; }
}
