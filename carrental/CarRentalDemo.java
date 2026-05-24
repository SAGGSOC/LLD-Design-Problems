package carrental;

import carrental.enums.VehicleType;
import carrental.exception.CarRentalException;
import carrental.exception.NoVehicleAvailableException;
import carrental.model.*;
import carrental.service.*;
import carrental.strategy.StandardPricingStrategy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CarRentalDemo {

    public static void main(String[] args) throws Exception {
        // ─── Setup ───
        AvailabilityIndex availabilityIndex = new AvailabilityIndex();
        StoreService storeService = new StoreService(availabilityIndex);
        CustomerService customerService = new CustomerService();
        PaymentGateway paymentGateway = new PaymentGateway(1.0);  // deterministic for demo
        BookingService bookingService = new BookingService(
            storeService, availabilityIndex, paymentGateway, new StandardPricingStrategy());

        // Stores
        Store sfoStore = new Store("S-SFO", "SFO Airport Rentals", "San Francisco",
            "1 Airport Dr");
        Store lasStore = new Store("S-LAS", "Vegas Strip Rentals", "Las Vegas",
            "3600 Las Vegas Blvd");
        storeService.addStore(sfoStore);
        storeService.addStore(lasStore);

        // Vehicles (2 economy, 2 SUV at SFO; 1 luxury at LAS)
        addCar(storeService, "V-1", "Toyota", "Corolla", 2024, VehicleType.ECONOMY, 45.00, "S-SFO");
        addCar(storeService, "V-2", "Honda", "Civic", 2024, VehicleType.ECONOMY, 45.00, "S-SFO");
        addCar(storeService, "V-3", "Toyota", "RAV4", 2024, VehicleType.SUV, 85.00, "S-SFO");
        addCar(storeService, "V-4", "Ford", "Explorer", 2024, VehicleType.SUV, 90.00, "S-SFO");
        addCar(storeService, "V-5", "BMW", "7 Series", 2024, VehicleType.LUXURY, 200.00, "S-LAS");

        // Customers
        Customer alice = customerService.registerCustomer(
            "Alice Johnson", "[email]", "555-0001", "DL-ABC123");
        Customer bob = customerService.registerCustomer(
            "Bob Smith", "[email]", "555-0002", "DL-XYZ789");

        LocalDate today = LocalDate.now();
        LocalDate thisFri = today.plusDays(daysUntilDayOfWeek(today, 5));  // Friday
        LocalDate thisSun = thisFri.plusDays(2);
        DateRange weekendRange = new DateRange(thisFri, thisSun);

        // ─── Scenario 1: Search available cars ───
        System.out.println("=== Scenario 1: Search ECONOMY at SFO for weekend ===");
        List<Vehicle> available = storeService.searchAvailable("S-SFO", VehicleType.ECONOMY, weekendRange);
        System.out.println("Available: " + available.size() + " vehicles");
        for (Vehicle v : available) {
            System.out.println("  " + v + " — $" + v.getDailyRate() + "/day");
        }
        System.out.println();

        // ─── Scenario 2: Reserve a car ───
        System.out.println("=== Scenario 2: Alice reserves ECONOMY ===");
        Reservation aliceRes = bookingService.reserve(
            alice, "S-SFO", "S-SFO", VehicleType.ECONOMY, weekendRange);
        System.out.println("Booked: " + aliceRes.getReservationId()
            + " — " + aliceRes.getVehicle()
            + " — $" + aliceRes.getEstimatedCost() + " (2 days × $45)");
        System.out.println();

        // ─── Scenario 3: Same dates — another ECONOMY still available ───
        System.out.println("=== Scenario 3: Bob reserves ECONOMY (same dates) ===");
        Reservation bobRes = bookingService.reserve(
            bob, "S-SFO", "S-SFO", VehicleType.ECONOMY, weekendRange);
        System.out.println("Bob got: " + bobRes.getVehicle());

        // ─── Scenario 4: Third reserve — should fail, only 2 ECONOMY cars ───
        System.out.println("\n=== Scenario 4: Third reservation (should fail) ===");
        Customer charlie = customerService.registerCustomer(
            "Charlie Lee", "[email]", "555-0003", "DL-CDE456");
        try {
            bookingService.reserve(charlie, "S-SFO", "S-SFO",
                VehicleType.ECONOMY, weekendRange);
            System.out.println("  [FAIL] should have rejected");
        } catch (NoVehicleAvailableException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }
        System.out.println();

        // ─── Scenario 5: Non-overlapping dates work fine ───
        System.out.println("=== Scenario 5: Different dates succeed ===");
        DateRange nextWeek = new DateRange(thisSun.plusDays(2), thisSun.plusDays(4));
        Reservation charlieRes = bookingService.reserve(
            charlie, "S-SFO", "S-SFO", VehicleType.ECONOMY, nextWeek);
        System.out.println("Charlie got " + charlieRes.getVehicle() + " for " + nextWeek);
        System.out.println();

        // ─── Scenario 6: One-way rental ───
        System.out.println("=== Scenario 6: One-way SFO → LAS ===");
        Reservation oneWayRes = bookingService.reserve(
            bob, "S-SFO", "S-LAS", VehicleType.SUV, weekendRange);
        System.out.println("Booked " + oneWayRes.getVehicle()
            + " — $" + oneWayRes.getEstimatedCost()
            + " (2 days × $85 + $50 one-way fee)");
        System.out.println();

        // ─── Scenario 7: Cancellation releases the car ───
        System.out.println("=== Scenario 7: Alice cancels ===");
        int availBefore = storeService.getAvailableCount(
            "S-SFO", VehicleType.ECONOMY, weekendRange);
        bookingService.cancelReservation(aliceRes.getReservationId());
        int availAfter = storeService.getAvailableCount(
            "S-SFO", VehicleType.ECONOMY, weekendRange);
        System.out.println("Available ECONOMY for weekend: " + availBefore + " → " + availAfter);
        System.out.println("Alice's res status: " + aliceRes.getStatus());
        System.out.println();

        // ─── Scenario 8: Concurrent reservation test ───
        System.out.println("=== Scenario 8: 20 customers race for last LUXURY ===");
        runConcurrencyTest(storeService, availabilityIndex, bookingService, customerService,
            "S-LAS", VehicleType.LUXURY,
            new DateRange(today.plusDays(10), today.plusDays(12)));
        System.out.println();

        // ─── Scenario 9: Pickup and return (need a rental pickup today) ───
        System.out.println("=== Scenario 9: Pickup and return flow ===");
        DateRange todayRange = new DateRange(today, today.plusDays(1));
        Reservation pickupRes = bookingService.reserve(
            alice, "S-SFO", "S-SFO", VehicleType.SUV, todayRange);
        System.out.println("Reserved SUV for today: " + pickupRes.getReservationId());

        Rental rental = bookingService.pickup(pickupRes.getReservationId());
        System.out.println("Picked up at " + rental.getActualPickupTime());
        System.out.println("Vehicle status: " + pickupRes.getVehicle().getStatus());

        // Return with 150 miles driven, no damage
        Rental returned = bookingService.returnVehicle(
            pickupRes.getReservationId(), 150, Collections.emptyList(), 0.0);
        System.out.println("Returned. Miles driven: " + returned.getMilesDriven());
        System.out.println("Final cost: $" + returned.getFinalCost());
        System.out.println("Vehicle status: " + pickupRes.getVehicle().getStatus());
        System.out.println();

        // ─── Scenario 10: Error handling ───
        System.out.println("=== Scenario 10: Error cases ===");
        testError(() -> bookingService.cancelReservation(returned.getReservation().getReservationId()),
            "cancel completed reservation");
        testError(() -> bookingService.pickup(aliceRes.getReservationId()),
            "pickup cancelled reservation");
        testError(() -> new DateRange(thisSun, thisFri),
            "return date before pickup date");
    }

    /** 20 threads racing for the last car. Exactly 1 should win. */
    private static void runConcurrencyTest(StoreService storeService,
                                            AvailabilityIndex availabilityIndex,
                                            BookingService bookingService,
                                            CustomerService customerService,
                                            String storeId, VehicleType type,
                                            DateRange dateRange) throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Customer racer = customerService.registerCustomer(
                "Racer" + i, "r" + i + "@e.com", "555", "DL-R" + i);
            executor.submit(() -> {
                try {
                    startGate.await();
                    bookingService.reserve(racer, storeId, storeId, type, dateRange);
                    successes.incrementAndGet();
                } catch (CarRentalException e) {
                    failures.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("  Successes: " + successes.get() + " (expected: 1)");
        System.out.println("  Failures:  " + failures.get() + " (expected: 19)");
        System.out.println("  Remaining: "
            + storeService.getAvailableCount(storeId, type, dateRange));

        if (successes.get() == 1 && failures.get() == 19) {
            System.out.println("  ✓ CONCURRENCY TEST PASSED");
        } else {
            System.out.println("  ✗ CONCURRENCY TEST FAILED");
        }
    }

    private static void addCar(StoreService storeService, String id, String make,
                                 String model, int year, VehicleType type,
                                 double rate, String storeId) {
        storeService.addVehicleToStore(new Vehicle(
            id, "VIN-" + id, "LIC-" + id, make, model, year, type, rate, 5, storeId));
    }

    /** Calculate days until the target day-of-week (1=Mon, 7=Sun). */
    private static int daysUntilDayOfWeek(LocalDate today, int targetDow) {
        int todayDow = today.getDayOfWeek().getValue();
        int diff = targetDow - todayDow;
        return diff <= 0 ? diff + 7 : diff;
    }

    private static void testError(Runnable op, String description) {
        try {
            op.run();
            System.out.println("  [FAIL] " + description + " — no exception");
        } catch (RuntimeException e) {
            System.out.println("  [OK]   " + description + " → " + e.getMessage());
        }
    }
}
