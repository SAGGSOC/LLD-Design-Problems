package parkinglot.gate;

import parkinglot.enums.VehicleType;
import parkinglot.exception.ParkingFullException;
import parkinglot.exception.SpotOccupiedException;
import parkinglot.exception.VehicleAlreadyParkedException;
import parkinglot.model.*;
import parkinglot.service.TicketService;
import parkinglot.strategy.SpotAssignmentStrategy;

import java.util.List;
import java.util.Optional;

/**
 * Entry gate — handles all vehicle types uniformly.
 * No vehicle-specific branching. Single-spot vs multi-spot
 * is determined by VehicleType.isMultiSpot() and handled
 * by the strategy + floor-level locking.
 */
public class EntryGate {
    private static final int MAX_RETRIES = 3;

    private final String gateId;
    private final ParkingLot parkingLot;
    private final SpotAssignmentStrategy spotAssigner;
    private final TicketService ticketService;

    public EntryGate(String gateId, ParkingLot parkingLot,
                     SpotAssignmentStrategy spotAssigner, TicketService ticketService) {
        this.gateId = gateId;
        this.parkingLot = parkingLot;
        this.spotAssigner = spotAssigner;
        this.ticketService = ticketService;
    }

    public Ticket processEntry(VehicleType vehicleType, String licensePlate, String color) {
        if (ticketService.getTicketByPlate(licensePlate) != null) {
            throw new VehicleAlreadyParkedException(licensePlate);
        }

        Vehicle vehicle = new Vehicle(licensePlate, vehicleType, color);

        if (vehicleType.isMultiSpot()) {
            return processMultiSpotEntry(vehicle, vehicleType);
        }

        return processSingleSpotEntry(vehicle, vehicleType);
    }

    private Ticket processSingleSpotEntry(Vehicle vehicle, VehicleType vehicleType) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Optional<List<ParkingSpot>> foundSpots = spotAssigner.findSpots(parkingLot, vehicleType);
            if (foundSpots.isEmpty()) {
                throw new ParkingFullException("No spots available for " + vehicleType);
            }

            ParkingSpot targetSpot = foundSpots.get().get(0);
            try {
                targetSpot.assignVehicle(vehicle);
                parkingLot.registerVehicleLocation(vehicle.getLicensePlate(), targetSpot);
                return ticketService.issueTicket(vehicle, foundSpots.get(), gateId);
            } catch (SpotOccupiedException e) {
                // Spot was taken between find and assign — retry
            }
        }
        throw new ParkingFullException(
                "Could not assign spot after " + MAX_RETRIES + " retries for " + vehicleType);
    }

    private Ticket processMultiSpotEntry(Vehicle vehicle, VehicleType vehicleType) {
        // Floor-level lock guarantees atomicity — no retry needed
        for (ParkingFloor floor : parkingLot.getFloors()) {
            List<ParkingSpot> assignedSpots = floor.assignMultiSpot(vehicle, vehicleType);
            if (assignedSpots != null) {
                parkingLot.registerVehicleLocation(vehicle.getLicensePlate(), assignedSpots.get(0));
                return ticketService.issueTicket(vehicle, assignedSpots, gateId);
            }
        }
        throw new ParkingFullException(
                "No " + vehicleType.getRequiredSpots() + " consecutive spots available for " + vehicleType);
    }

    public String getGateId() { return gateId; }
}
