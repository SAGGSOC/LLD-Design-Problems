package parkinglot.model;

import parkinglot.enums.VehicleType;

public class Vehicle {
    private final String licensePlate;
    private final VehicleType type;
    private final String color;

    public Vehicle(String licensePlate, VehicleType type, String color) {
        this.licensePlate = licensePlate;
        this.type = type;
        this.color = color;
    }

    public String getLicensePlate() { return licensePlate; }
    public VehicleType getType()    { return type; }
    public String getColor()        { return color; }
}
