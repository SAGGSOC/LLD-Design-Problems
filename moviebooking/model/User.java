package moviebooking.model;

public class User {
    private final String userId;
    private final String name;
    private final String email;
    private final String phone;

    public User(String userId, String name, String email, String phone) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    public String getUserId() { return userId; }
    public String getName()   { return name; }
    public String getEmail()  { return email; }
    public String getPhone()  { return phone; }

    @Override
    public String toString() { return name + "(" + userId + ")"; }
}
