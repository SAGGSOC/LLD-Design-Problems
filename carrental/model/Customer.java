package carrental.model;

public class Customer {
    private final String customerId;
    private final String name;
    private final String email;
    private final String phone;
    private final String driversLicense;

    public Customer(String customerId, String name, String email,
                    String phone, String driversLicense) {
        this.customerId = customerId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.driversLicense = driversLicense;
    }

    public String getCustomerId()      { return customerId; }
    public String getName()            { return name; }
    public String getEmail()           { return email; }
    public String getPhone()           { return phone; }
    public String getDriversLicense()  { return driversLicense; }

    @Override
    public String toString() { return name + "(" + customerId + ")"; }
}
