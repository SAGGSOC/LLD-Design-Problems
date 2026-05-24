package carrental.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Active or completed rental. Created on pickup, finalized on return.
 * Tracks actual timestamps, odometer readings, damage reports — all the info
 * needed to compute the final bill.
 */
public class Rental {
    private final String rentalId;
    private final Reservation reservation;
    private final Instant actualPickupTime;
    private final int pickupOdometer;

    private Instant actualReturnTime;
    private int returnOdometer;
    private final List<String> damageReports = new ArrayList<>();
    private double finalCost;
    private boolean completed;

    public Rental(String rentalId, Reservation reservation,
                  Instant actualPickupTime, int pickupOdometer) {
        this.rentalId = rentalId;
        this.reservation = reservation;
        this.actualPickupTime = actualPickupTime;
        this.pickupOdometer = pickupOdometer;
    }

    public void addDamageReport(String description) {
        damageReports.add(description);
    }

    public void complete(Instant returnTime, int returnOdometer, double finalCost) {
        this.actualReturnTime = returnTime;
        this.returnOdometer = returnOdometer;
        this.finalCost = finalCost;
        this.completed = true;
    }

    public int getMilesDriven() {
        return completed ? returnOdometer - pickupOdometer : 0;
    }

    public String getRentalId()             { return rentalId; }
    public Reservation getReservation()     { return reservation; }
    public Instant getActualPickupTime()    { return actualPickupTime; }
    public int getPickupOdometer()          { return pickupOdometer; }
    public Instant getActualReturnTime()    { return actualReturnTime; }
    public int getReturnOdometer()          { return returnOdometer; }
    public List<String> getDamageReports()  { return damageReports; }
    public double getFinalCost()            { return finalCost; }
    public boolean isCompleted()            { return completed; }
}
