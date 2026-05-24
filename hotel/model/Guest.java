package hotel.model;

import java.util.Objects;

public class Guest {
    private final String guestId;
    private final String name;
    private final String email;
    private final String phone;
    private final String idProof;  // passport / driver's license number

    public Guest(String guestId, String name, String email, String phone, String idProof) {
        this.guestId = guestId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.idProof = idProof;
    }

    public String getGuestId() { return guestId; }
    public String getName()    { return name; }
    public String getEmail()   { return email; }
    public String getPhone()   { return phone; }
    public String getIdProof() { return idProof; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Guest)) return false;
        return Objects.equals(guestId, ((Guest) o).guestId);
    }

    @Override
    public int hashCode() { return Objects.hash(guestId); }

    @Override
    public String toString() { return name + "(" + guestId + ")"; }
}
