package cricinfo.model;

import java.util.Objects;

public class Player {
    private final String playerId;
    private final String name;
    private final String country;
    private final String role;   // "batsman", "bowler", "all-rounder", "wicket-keeper"

    public Player(String playerId, String name, String country, String role) {
        this.playerId = playerId;
        this.name = name;
        this.country = country;
        this.role = role;
    }

    public String getPlayerId() { return playerId; }
    public String getName()     { return name; }
    public String getCountry()  { return country; }
    public String getRole()     { return role; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Player)) return false;
        return Objects.equals(playerId, ((Player) o).playerId);
    }

    @Override
    public int hashCode() { return Objects.hash(playerId); }

    @Override
    public String toString() { return name; }
}
