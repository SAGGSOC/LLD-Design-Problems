package cricinfo.model;

import java.util.List;
import java.util.Objects;

public class Team {
    private final String teamId;
    private final String name;
    private final String country;
    private final List<Player> players;
    private final Player captain;

    public Team(String teamId, String name, String country,
                List<Player> players, Player captain) {
        this.teamId = teamId;
        this.name = name;
        this.country = country;
        this.players = players;
        this.captain = captain;
    }

    public String getTeamId()         { return teamId; }
    public String getName()           { return name; }
    public String getCountry()        { return country; }
    public List<Player> getPlayers()  { return players; }
    public Player getCaptain()        { return captain; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Team)) return false;
        return Objects.equals(teamId, ((Team) o).teamId);
    }

    @Override
    public int hashCode() { return Objects.hash(teamId); }

    @Override
    public String toString() { return name; }
}
