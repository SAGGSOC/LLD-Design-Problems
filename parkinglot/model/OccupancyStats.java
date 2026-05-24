package parkinglot.model;

public class OccupancyStats {
    private final int totalCapacity;
    private final int occupied;
    private final int available;

    public OccupancyStats(int totalCapacity, int occupied, int available) {
        this.totalCapacity = totalCapacity;
        this.occupied = occupied;
        this.available = available;
    }

    public int getTotalCapacity() { return totalCapacity; }
    public int getOccupied()      { return occupied; }
    public int getAvailable()     { return available; }

    public double getOccupancyRate() {
        return totalCapacity == 0 ? 0.0 : (double) occupied / totalCapacity * 100;
    }

    @Override
    public String toString() {
        return String.format("Total: %d, Occupied: %d, Available: %d (%.1f%%)",
                totalCapacity, occupied, available, getOccupancyRate());
    }
}
