package parkinglot;

import parkinglot.enums.VehicleType;
import parkinglot.gate.EntryGate;
import parkinglot.gate.ExitGate;
import parkinglot.model.*;
import parkinglot.service.FeeCalculator;
import parkinglot.service.TicketService;
import parkinglot.strategy.NearestFirstStrategy;
import parkinglot.strategy.SpotAssignmentStrategy;

public class ParkingLotDemo {

    public static void main(String[] args) throws InterruptedException {
        ParkingLot lot = ParkingLotBuilder.buildDefaultLot();
        System.out.println("=== " + lot.getName() + " ===");
        System.out.println("Total capacity: " + lot.getTotalCapacity());
        System.out.println();

        TicketService ticketService = new TicketService();
        FeeCalculator feeCalculator = new FeeCalculator();
        SpotAssignmentStrategy strategy = new NearestFirstStrategy();

        EntryGate gateA = new EntryGate("ENTRY-A", lot, strategy, ticketService);
        EntryGate gateB = new EntryGate("ENTRY-B", lot, strategy, ticketService);
        ExitGate exitC = new ExitGate("EXIT-C", lot, ticketService, feeCalculator);

        // --- Car enters ---
        System.out.println("--- Car ABC-1234 enters Gate A ---");
        Ticket t1 = gateA.processEntry(VehicleType.CAR, "ABC-1234", "Blue");
        printTicket(t1);

        // --- Truck enters ---
        System.out.println("--- Truck TRK-5678 enters Gate B ---");
        Ticket t2 = gateB.processEntry(VehicleType.TRUCK, "TRK-5678", "Red");
        printTicket(t2);

        // --- Motorcycle enters ---
        System.out.println("--- Motorcycle BIKE-99 enters Gate A ---");
        Ticket t3 = gateA.processEntry(VehicleType.MOTORCYCLE, "BIKE-99", "Black");
        printTicket(t3);

        // --- Bus enters (needs 3 consecutive LARGE spots) ---
        System.out.println("--- Bus BUS-4242 enters Gate A ---");
        Ticket t4 = gateA.processEntry(VehicleType.BUS, "BUS-4242", "Yellow");
        System.out.println("Ticket: " + t4.getTicketId());
        System.out.println("Spots occupied: " + t4.getSpots().size());
        for (ParkingSpot s : t4.getSpots()) {
            System.out.println("  Floor " + s.getFloorNumber()
                    + ", Spot " + s.getSpotNumber() + " (" + s.getSpotType() + ")");
        }
        System.out.println();

        // --- Availability ---
        System.out.println("--- Availability ---");
        System.out.println("Cars:        " + lot.getAvailableCount(VehicleType.CAR));
        System.out.println("Trucks:      " + lot.getAvailableCount(VehicleType.TRUCK));
        System.out.println("Motorcycles: " + lot.getAvailableCount(VehicleType.MOTORCYCLE));
        System.out.println("Buses:       " + lot.getAvailableCount(VehicleType.BUS)
                + " (groups of 3 consecutive LARGE)");
        System.out.println("Occupancy:   " + lot.getOccupancyRate());
        System.out.println();

        // --- Simulate parking time ---
        System.out.println("--- Simulating 2 seconds of parking time... ---");
        Thread.sleep(2000);

        // --- Bus exits ---
        System.out.println("--- Bus BUS-4242 exits Gate C ---");
        Payment p4 = exitC.processExit(t4.getTicketId());
        System.out.println(p4);
        System.out.println();

        // --- Car exits ---
        System.out.println("--- Car ABC-1234 exits Gate C ---");
        Payment p1 = exitC.processExit(t1.getTicketId());
        System.out.println(p1);
        System.out.println();

        // --- Concurrent bus + car entry ---
        System.out.println("--- Concurrent entry: Bus at Gate A, Car at Gate B ---");
        Thread threadA = new Thread(() -> {
            Ticket t = gateA.processEntry(VehicleType.BUS, "BUS-9999", "Green");
            System.out.println("[Gate A] Bus " + t.getTicketId() + " → "
                    + t.getSpots().size() + " spots on Floor "
                    + t.getSpot().getFloorNumber());
        });
        Thread threadB = new Thread(() -> {
            Ticket t = gateB.processEntry(VehicleType.CAR, "RACE-002", "Silver");
            System.out.println("[Gate B] Car " + t.getTicketId() + " → Floor "
                    + t.getSpot().getFloorNumber() + ", Spot " + t.getSpot().getSpotNumber());
        });
        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();

        System.out.println();
        System.out.println("--- Final occupancy ---");
        System.out.println(lot.getOccupancyRate());
    }

    private static void printTicket(Ticket t) {
        System.out.println("Ticket: " + t.getTicketId());
        System.out.println("Spot: Floor " + t.getSpot().getFloorNumber()
                + ", Spot " + t.getSpot().getSpotNumber()
                + " (" + t.getSpot().getSpotType() + ")");
        System.out.println();
    }
}
