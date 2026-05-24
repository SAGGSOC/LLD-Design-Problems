package airline.service;

import airline.enums.FareClass;
import airline.model.Flight;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class FlightService {
    private final Map<String, Flight> flightsByNumber = new ConcurrentHashMap<>();

    public void addFlight(Flight flight) {
        flightsByNumber.put(flight.getFlightNumber(), flight);
    }

    public Flight getFlight(String flightNumber) {
        Flight flight = flightsByNumber.get(flightNumber);
        if (flight == null) throw new IllegalArgumentException("Flight not found: " + flightNumber);
        return flight;
    }

    /**
     * Search flights matching origin, destination, date, and fare class with availability.
     * Sorted by departure time.
     */
    public List<Flight> search(String originCode, String destinationCode,
                               LocalDate date, FareClass fareClass) {
        return flightsByNumber.values().stream()
            .filter(flight -> flight.getOrigin().getCode().equalsIgnoreCase(originCode))
            .filter(flight -> flight.getDestination().getCode().equalsIgnoreCase(destinationCode))
            .filter(flight -> {
                LocalDate departDate = flight.getScheduledDeparture()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
                return departDate.equals(date);
            })
            .filter(Flight::isBookable)
            .filter(flight -> flight.getAvailableSeats(fareClass) > 0)
            .sorted(Comparator.comparing(Flight::getScheduledDeparture))
            .collect(Collectors.toList());
    }
}
