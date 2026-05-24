package carrental.model;

import carrental.enums.VehicleStatus;
import carrental.enums.VehicleType;

public class Vehicle {
    private final String vehicleId;      // internal id
    private final String vin;            // VIN number
    private final String licensePlate;
    private final String make;           // "Toyota"
    private final String model;          // "Camry"
    private final int year;
    private final VehicleType type;
    private final double dailyRate;      // base rate per day
    private final int seatingCapacity;

    private String currentStoreId;       // may change if moved between stores
    private VehicleStatus status;
    private int odometer;                // miles

    public Vehicle(String vehicleId, String vin, String licensePlate,
                   String make, String model, int year,
                   VehicleType type, double dailyRate, int seatingCapacity,
                   String homeStoreId) {
        this.vehicleId = vehicleId;
        this.vin = vin;
        this.licensePlate = licensePlate;
        this.make = make;
        this.model = model;
        this.year = year;
        this.type = type;
        this.dailyRate = dailyRate;
        this.seatingCapacity = seatingCapacity;
        this.currentStoreId = homeStoreId;
        this.status = VehicleStatus.AVAILABLE;
        this.odometer = 0;
    }

    public synchronized void markRented()       { this.status = VehicleStatus.RENTED; }
    public synchronized void markAvailable()    { this.status = VehicleStatus.AVAILABLE; }
    public synchronized void markMaintenance()  { this.status = VehicleStatus.MAINTENANCE; }

    public void moveToStore(String storeId) { this.currentStoreId = storeId; }
    public void setOdometer(int odometer)   { this.odometer = odometer; }

    public boolean isRentable() {
        return status != VehicleStatus.MAINTENANCE;
    }

    public String getVehicleId()        { return vehicleId; }
    public String getVin()              { return vin; }
    public String getLicensePlate()     { return licensePlate; }
    public String getMake()             { return make; }
    public String getModel()            { return model; }
    public int getYear()                { return year; }
    public VehicleType getType()        { return type; }
    public double getDailyRate()        { return dailyRate; }
    public int getSeatingCapacity()     { return seatingCapacity; }
    public String getCurrentStoreId()   { return currentStoreId; }
    public VehicleStatus getStatus()    { return status; }
    public int getOdometer()            { return odometer; }

    @Override
    public String toString() {
        return year + " " + make + " " + model + " (" + licensePlate + ")";
    }
}
