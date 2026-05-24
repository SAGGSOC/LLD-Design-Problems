package airline.model;

public class Passenger {
    private final String passengerId;
    private final String name;
    private final String email;
    private final String passportNumber;

    public Passenger(String passengerId, String name, String email, String passportNumber) {
        this.passengerId = passengerId;
        this.name = name;
        this.email = email;
        this.passportNumber = passportNumber;
    }

    public String getPassengerId()    { return passengerId; }
    public String getName()           { return name; }
    public String getEmail()          { return email; }
    public String getPassportNumber() { return passportNumber; }

    @Override
    public String toString() { return name + "(" + passengerId + ")"; }
}
