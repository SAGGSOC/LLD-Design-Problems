package moviebooking.model;

import java.util.List;

public class Screen {
    private final String screenId;
    private final String name;       // e.g. "Screen 1", "IMAX"
    private final int totalSeats;
    private final List<Seat> seats;  // layout defined at screen level
    private final String cinemaId;

    public Screen(String screenId, String name, String cinemaId, List<Seat> seats) {
        this.screenId = screenId;
        this.name = name;
        this.cinemaId = cinemaId;
        this.seats = seats;
        this.totalSeats = seats.size();
    }

    public String getScreenId()   { return screenId; }
    public String getName()       { return name; }
    public String getCinemaId()   { return cinemaId; }
    public int getTotalSeats()    { return totalSeats; }
    public List<Seat> getSeats()  { return seats; }
}
