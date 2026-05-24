package parkinglot;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import carrental.strategy.PricingStrategy;
import parkinglot.model.Ticket;

class ParkingSpot{
    private final String spotId;
    private boolean isFree = false;
    public ParkingSpot(String spotId){
        this.spotId = spotId;
    }
    public boolean isSpotFree(){
        return isFree;
    }
    public void occupySpot(){
        isFree = false;
    }
    public void releaseSpot(){
        isFree = true;
    }
    public String getSpotId(){
        return spotId;
    }
}
enum VehicleType{
    TWO_WHEELER,
    FOUR_WHEELER
}
class Vehicle{
    String vehicleNum;
    VehicleType vehicleType;
    public Vehicle(String vehicleNum, VehicleType vehicleType){
        this.vehicleNum = vehicleNum;
        this.vehicleType = vehicleType;
    }
}
class EntranceGate{
    public Ticket enter(ParkingBuilding buidling, Vehicle vehicle){
        buidling.allocate(vehicle);
    }
}
class ExitGate{
    private final CostComputation costComputation;
    public ExitGate(CostComputation costComputation){
        this.costComputation = costComputation;
    }
    public void completeExit(ParkingBuilding buidling, Ticket ticket, Payment payment){
        double amount = calculatePrice(ticket);
        boolean success =  payment.pay(amount);
        if(!success){
            throw new RunTimeException("Payment Failed. EXIT DENIED");
        }
        buidling.release(ticket);
        System.out.println("Exit successful. Gate opened.");
    }
    private double calculatePrice(Ticket ticket){
        costComputation.compute(ticket);
    }
}
class CostComputation{
    private final PricingStrategy pricingStrategy;
    public CostComputation(PricingStrategy pricingStrategy){
        this.pricingStrategy = pricingStrategy;
    }
    public double compute(Ticket ticket){
        return pricingStrategy.calculate(ticket);
    }
}
class FixedPricingStrategy implements PricingStrategy{
    @Override
    public double calculate(Ticket ticket){
        return 100;
    }
}
interface PricingStrategy {

    double calculate(Ticket ticket);
}
class ParkingLot{
    private final ParkingBuilding building;
    private final EntranceGate entranceGate;
    private final ExitGate exitGate;

    public ParkingLot(ParkingBuilding building,
                      EntranceGate entranceGate,
                      ExitGate exitGate) {
        this.building = building;
        this.entranceGate = entranceGate;
        this.exitGate = exitGate;
    }

    public Ticket vehicleArrives(Vehicle vehicle) {
        return entranceGate.enter(building, vehicle);
    }

    public void vehicleExits(Ticket ticket, Payment payment) {
        exitGate.completeExit(building, ticket, payment);
    }

}
interface Payment {
    boolean pay(double amount);
}
class UPIPayment implements Payment {
    @Override
    public boolean pay(double amount) {
        System.out.println("UPI paid: " + amount);
        return true;
    }
}
class CashPayment implements Payment {
    @Override
    public boolean pay(double amount) {
        System.out.println("Cash paid: " + amount);
        return true;
    }
}
class ParkingBuilding{
    private final List<ParkingLevel> levels;
    Ticket allocate(Vehicle vehicle){
        if(levels.hasAvailability(vehicle.getVehicleType())){
            ParkingSpot spot = level.park(vehicle.getVehicleType);
            if(spot != null){
                Ticket ticket = new Ticket(vehicle, level, spot);
                System.out.println("parking allocated");
                return ticket;
            }
        }
        throw new RuntimeException("Parking Full");
    }
    void release(Ticket ticket) {
        ticket.getLevel().unPark(
                ticket.getVehicle().getVehicleType(),
                ticket.getSpot()
        );
    }
}
class ParkingLevel{
    private int levelNum;
    private final Map<VehicleType,ParkingSpotManager> managers;
    public ParkingLevel(int levelNum, Map<VehicleType, ParkingSpotManager> managers){
        this.levelNum = levelNum;
        this.managers = managers;
    }
    public boolean hasAvailability(VehicleType type){
        ParkingSpotManager manager = managers.get(type);
        return manager != null && manager.hasFreeSpot();
    }
    public ParkingSpot park(VehicleType type){
        ParkingSpotManager manager = managers.get(type);
        if(manager == null){
            throw new IllegalArgumentException(
                    "No parking manager for vehicle type: " + type);
        }
        return manager.park();
    }
    public void unPark(VehicleType type, ParkingSpot spot) {
        ParkingSpotManager manager = managers.get(type);
        if (manager != null) {
            manager.unPark(spot);
        }
    }
    public int getLevelNumber() {
        return levelNum;
    }
}
class ParkingSpotManager{
    protected final List<ParkingSpot> spots;
    protected final ParkingSpotlookupStrategy ParkingSpotlookupStrategy;
    private final ReentrantLock lock = new ReentrantLock(true);
    protected ParkingSpotManager(List<ParkingSpot> spots, ParkingSpotLookupStrategy strategy){
        this.spots = spots;
        this.strategy = strategy;
    }
    public ParkingSpot park(){
        lock.lock();;
        try{
            ParkingSpot spot = strategy.selectSpot(spots);
            if(spot == null){
                return null;
            }
            spot.occupySpot();
            return spot;
        } finally{
            lock.unlock();
        }
    }
    public void unPark(ParkingSpot spot){
        lock.lock();
        try{
            spot.releaseSpot();
        } finally {
            lock.unlock();
        }
    }
    public boolean hasFreeSpot(){
        lock.lock();
        try{
            return spots.stream().anyMatch(ParkingSpot::isSpotFree);
        } finally {
            lock.unlock();
        }
    }
}
class FourWheelerSpotStrategy extends ParkingLotSpotManager{
    public FourWheelerSpotStrategy(List<ParkingSpot> spots,ParkingSpotLookupStrategy strategy){
        super(spots, strategy);
    }
}
class Ticket{
    private final Vehicle vehicle;
    private final ParkingLevel level;
    private final ParkingSpot spot;
    private final LocalDateTime entryTime;

    public Ticket(Vehicle vehicle,
                  ParkingLevel level,
                  ParkingSpot spot) {
        this.vehicle = vehicle;
        this.level = level;
        this.spot = spot;
        this.entryTime = LocalDateTime.now();
    }
    public Vehicle getVehicle() {
        return vehicle;
    }
    public ParkingLevel getLevel() {
        return level;
    }
    public ParkingSpot getSpot() {
        return spot;
    }
    public LocalDateTime getEntryTime() {
        return entryTime;
    }
}
public class ParkingLotv2 {
    
}
