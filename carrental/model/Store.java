package carrental.model;

public class Store {
    private final String storeId;
    private final String name;
    private final String city;
    private final String address;

    public Store(String storeId, String name, String city, String address) {
        this.storeId = storeId;
        this.name = name;
        this.city = city;
        this.address = address;
    }

    public String getStoreId()  { return storeId; }
    public String getName()     { return name; }
    public String getCity()     { return city; }
    public String getAddress()  { return address; }

    @Override
    public String toString() { return name + " (" + city + ")"; }
}
