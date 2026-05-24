package moviebooking.model;

import java.util.List;

public class Cinema {
    private final String cinemaId;
    private final String name;
    private final String city;
    private final String address;
    private final List<Screen> screens;

    public Cinema(String cinemaId, String name, String city,
                  String address, List<Screen> screens) {
        this.cinemaId = cinemaId;
        this.name = name;
        this.city = city;
        this.address = address;
        this.screens = screens;
    }

    public String getCinemaId()       { return cinemaId; }
    public String getName()           { return name; }
    public String getCity()           { return city; }
    public String getAddress()        { return address; }
    public List<Screen> getScreens()  { return screens; }
}
