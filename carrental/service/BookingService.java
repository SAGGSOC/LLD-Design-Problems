package carrental.service;

import carrental.enums.PaymentStatus;
import carrental.enums.ReservationStatus;
import carrental.enums.VehicleType;
import carrental.exception.InvalidReservationException;
import carrental.exception.NoVehicleAvailableException;
import carrental.exception.ReservationNotFoundException;
import carrental.model.*;
import carrental.strategy.PricingStrategy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the full rental lifecycle:
 *   reserve  → pickup   → return
 *
 * Concurrency: `reserve()` is synchronized to prevent two customers
 * from reserving the last available car for overlapping dates.
 * The AvailabilityIndex.tryReserve() is the atomic primitive.
 *
 * Payment flow:
 *   - On reservation: charge estimated cost
 *   - On return: calculate actual cost; charge difference (late fee, one-way, damage)
 *     or refund if less than estimate
 */
public class BookingService {
    private final Map<String, Reservation> reservationsById = new ConcurrentHashMap<>();
    private final Map<String, Rental> rentalsById = new ConcurrentHashMap<>();
    private final Map<String, String> rentalByReservationId = new ConcurrentHashMap<>();
    private final Map<String, List<String>> reservationsByCustomerId = new ConcurrentHashMap<>();

    private final StoreService storeService;
    private final AvailabilityIndex availabilityIndex;
    private final PaymentGateway paymentGateway;
    private final PricingStrategy pricingStrategy;

    public BookingService(StoreService storeService, AvailabilityIndex availabilityIndex,
                          PaymentGateway paymentGateway, PricingStrategy pricingStrategy) {
        this.storeService = storeService;
        this.availabilityIndex = availabilityIndex;
        this.paymentGateway = paymentGateway;
        this.pricingStrategy = pricingStrategy;
    }

    // ──────────────────────── Reserve ────────────────────────

    public synchronized Reservation reserve(Customer customer, String pickupStoreId,
                                             String returnStoreId, VehicleType type,
                                             DateRange dateRange) {
        // Find first available vehicle of requested type at pickup store
        List<Vehicle> candidates =
            storeService.searchAvailable(pickupStoreId, type, dateRange);
        if (candidates.isEmpty()) {
            throw new NoVehicleAvailableException(
                "No " + type + " vehicles available at store " + pickupStoreId
                + " for " + dateRange);
        }

        Vehicle selected = candidates.get(0);

        // Atomic check-and-reserve — covers the "last car race"
        if (!availabilityIndex.tryReserve(selected.getVehicleId(), dateRange)) {
            throw new NoVehicleAvailableException(
                "Vehicle " + selected.getVehicleId() + " just got reserved by someone else");
        }

        Store pickup = storeService.getStore(pickupStoreId);
        Store ret = storeService.getStore(returnStoreId);

        double basePrice = pricingStrategy.estimatePrice(selected, dateRange);
        double oneWayFee = pricingStrategy.calculateOneWayFee(pickupStoreId, returnStoreId);
        double estimatedCost = basePrice + oneWayFee;

        // Charge estimated cost up-front
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        Payment payment = paymentGateway.charge(reservationId, estimatedCost);

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            // Rollback — release the reserved range
            availabilityIndex.release(selected.getVehicleId(), dateRange);
            throw new InvalidReservationException(
                "Payment failed for reservation " + reservationId);
        }

        Reservation reservation = new Reservation(
            reservationId, customer, selected, pickup, ret, dateRange, estimatedCost);
        reservation.setPaymentId(payment.getPaymentId());
        reservationsById.put(reservationId, reservation);
        reservationsByCustomerId
            .computeIfAbsent(customer.getCustomerId(), k -> new ArrayList<>())
            .add(reservationId);
        return reservation;
    }

    // ──────────────────────── Pickup ────────────────────────

    public Rental pickup(String reservationId) {
        Reservation reservation = getReservation(reservationId);

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidReservationException(
                "Cannot pickup — status is " + reservation.getStatus());
        }

        // Window check: pickup allowed between scheduled pickup date and end of day
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate pickupDate = reservation.getDateRange().getPickupDate();
        if (today.isBefore(pickupDate)) {
            throw new InvalidReservationException(
                "Too early — pickup not before " + pickupDate);
        }
        // In production we'd also check we're not past the return date

        Vehicle vehicle = reservation.getVehicle();
        vehicle.markRented();

        String rentalId = "RNT-" + UUID.randomUUID().toString().substring(0, 8);
        Rental rental = new Rental(rentalId, reservation, Instant.now(), vehicle.getOdometer());
        rentalsById.put(rentalId, rental);
        rentalByReservationId.put(reservationId, rentalId);

        reservation.setStatus(ReservationStatus.PICKED_UP);
        return rental;
    }

    // ──────────────────────── Return ────────────────────────

    /**
     * Close out the rental. Calculate final charges:
     *   - Late fee (if returned after scheduled return date)
     *   - Damage fee (if damage reported)
     *   - Charge difference from estimated cost, or refund if over-estimated
     */
    public Rental returnVehicle(String reservationId, int returnOdometer,
                                 List<String> damageDescriptions, double damageCharge) {
        Reservation reservation = getReservation(reservationId);
        if (reservation.getStatus() != ReservationStatus.PICKED_UP) {
            throw new InvalidReservationException(
                "Cannot return — status is " + reservation.getStatus());
        }

        String rentalId = rentalByReservationId.get(reservationId);
        Rental rental = rentalsById.get(rentalId);
        if (rental == null) throw new IllegalStateException("No rental for " + reservationId);

        Instant returnTime = Instant.now();
        Vehicle vehicle = reservation.getVehicle();

        // Calculate late fee
        Instant scheduledReturn = reservation.getDateRange().getReturnDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant();
        long hoursLate = Math.max(0,
            Duration.between(scheduledReturn, returnTime).toHours());
        double lateFee = pricingStrategy.calculateLateReturnFee(vehicle, hoursLate);

        // Record damage
        for (String desc : damageDescriptions) rental.addDamageReport(desc);

        double finalCost = reservation.getEstimatedCost() + lateFee + damageCharge;

        // Payment adjustment
        double adjustment = finalCost - reservation.getEstimatedCost();
        if (adjustment > 0.01) {
            paymentGateway.chargeAdditional(reservationId, adjustment);
        } else if (adjustment < -0.01) {
            paymentGateway.refund(reservation.getPaymentId(), -adjustment);
        }

        // Finalize state
        rental.complete(returnTime, returnOdometer, finalCost);
        vehicle.setOdometer(returnOdometer);
        vehicle.markAvailable();
        // If one-way rental, move the vehicle to the return store
        if (!reservation.getPickupStore().getStoreId()
                .equals(reservation.getReturnStore().getStoreId())) {
            vehicle.moveToStore(reservation.getReturnStore().getStoreId());
        }
        reservation.setStatus(ReservationStatus.COMPLETED);
        return rental;
    }

    // ──────────────────────── Cancel ────────────────────────

    public synchronized void cancelReservation(String reservationId) {
        Reservation reservation = getReservation(reservationId);
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidReservationException(
                "Cannot cancel — status is " + reservation.getStatus());
        }

        // Refund
        paymentGateway.refund(reservation.getPaymentId(), reservation.getEstimatedCost());

        // Release the reserved date range
        availabilityIndex.release(
            reservation.getVehicle().getVehicleId(), reservation.getDateRange());
        reservation.setStatus(ReservationStatus.CANCELLED);
    }

    // ──────────────────────── Getters ────────────────────────

    public Reservation getReservation(String reservationId) {
        Reservation r = reservationsById.get(reservationId);
        if (r == null) throw new ReservationNotFoundException(reservationId);
        return r;
    }

    public Rental getRental(String rentalId) {
        return rentalsById.get(rentalId);
    }

    public List<Reservation> getCustomerReservations(String customerId) {
        List<String> ids = reservationsByCustomerId.getOrDefault(
            customerId, Collections.emptyList());
        List<Reservation> result = new ArrayList<>();
        for (String id : ids) {
            Reservation r = reservationsById.get(id);
            if (r != null) result.add(r);
        }
        return result;
    }
}
