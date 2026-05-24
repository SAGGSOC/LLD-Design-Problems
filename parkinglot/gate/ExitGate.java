package parkinglot.gate;

import parkinglot.model.ParkingLot;
import parkinglot.model.ParkingSpot;
import parkinglot.model.Payment;
import parkinglot.model.Ticket;
import parkinglot.service.FeeCalculator;
import parkinglot.service.TicketService;

public class ExitGate {
    private final String gateId;
    private final ParkingLot parkingLot;
    private final TicketService ticketService;
    private final FeeCalculator feeCalculator;

    public ExitGate(String gateId, ParkingLot parkingLot,
                    TicketService ticketService, FeeCalculator feeCalculator) {
        this.gateId = gateId;
        this.parkingLot = parkingLot;
        this.ticketService = ticketService;
        this.feeCalculator = feeCalculator;
    }

    public Payment processExit(String ticketId) {
        Ticket closedTicket = ticketService.closeTicket(ticketId);
        Payment payment = feeCalculator.calculate(closedTicket);

        // Unregister from vehicle location map (O(1) cleanup)
        parkingLot.unregisterVehicleLocation(closedTicket.getVehicle().getLicensePlate());

        // Free all spots
        for (ParkingSpot occupiedSpot : closedTicket.getSpots()) {
            occupiedSpot.removeVehicle();
        }

        return payment;
    }

    public String getGateId() { return gateId; }
}
