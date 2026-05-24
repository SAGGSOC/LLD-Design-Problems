package carrental.service;

import carrental.model.Customer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CustomerService {
    private final Map<String, Customer> customersById = new ConcurrentHashMap<>();

    public Customer registerCustomer(String name, String email, String phone,
                                     String driversLicense) {
        String customerId = "C-" + (customersById.size() + 1);
        Customer customer = new Customer(customerId, name, email, phone, driversLicense);
        customersById.put(customerId, customer);
        return customer;
    }

    public Customer getCustomer(String customerId) {
        Customer customer = customersById.get(customerId);
        if (customer == null) throw new IllegalArgumentException("Customer not found: " + customerId);
        return customer;
    }
}
