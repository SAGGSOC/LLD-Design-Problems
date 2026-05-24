package inventory.service;

import inventory.exception.ProductNotFoundException;
import inventory.model.Product;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProductService {
    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public Product addProduct(Product product) {
        products.put(product.getProductId(), product);
        return product;
    }

    public Product getProduct(String productId) {
        Product p = products.get(productId);
        if (p == null) throw new ProductNotFoundException(productId);
        return p;
    }

    public Collection<Product> getAllProducts() {
        return products.values();
    }

    public Map<String, Product> getProductMap() {
        return products;
    }
}
