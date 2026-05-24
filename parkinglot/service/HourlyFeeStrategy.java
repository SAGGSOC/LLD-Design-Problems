package parkinglot.service;

import parkinglot.enums.TicketStatus;
import parkinglot.enums.VehicleType;
import parkinglot.model.Payment;
import parkinglot.model.Ticket;

import java.time.Duration;
import java.util.Map;

/**
 * Default fee strategy: per-hour billing with round-up on partial hours.
 */
public class HourlyFeeStrategy implements FeeStrategy {
    private final Map<VehicleType, Double> hourlyRates;
    private final double lostTicketPenalty;

    public HourlyFeeStrategy(Map<VehicleType, Double> hourlyRates, double lostTicketPenalty) {
        this.hourlyRates = hourlyRates;
        this.lostTicketPenalty = lostTicketPenalty;
    }

    public HourlyFeeStrategy() {
        this(Map.of(
                VehicleType.MOTORCYCLE, 2.0,
                VehicleType.CAR,        5.0,
                VehicleType.TRUCK,      10.0,
                VehicleType.BUS,        15.0
        ), 50.0);
    }

    @Override
    public Payment calculate(Ticket ticket) {
        if (ticket.getStatus() == TicketStatus.LOST) {
            return new Payment(ticket.getTicketId(), lostTicketPenalty,
                    ticket.getDuration(), ticket.getVehicle().getType());
        }

        Duration parkingDuration = ticket.getDuration();
        long billableHours = parkingDuration.toHours();
        if (parkingDuration.toMinutesPart() > 0) billableHours++;

        double hourlyRate = hourlyRates.getOrDefault(ticket.getVehicle().getType(), 5.0);
        double totalAmount = billableHours * hourlyRate;

        return new Payment(ticket.getTicketId(), totalAmount, parkingDuration,
                ticket.getVehicle().getType());
    }
}
