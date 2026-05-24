package inventory.model;

import inventory.enums.Category;

public class Product {
    private final String productId;
    private final String name;
    private final Category category;
    private final double price;
    private final int lowStockThreshold;

    public Product(String productId, String name, Category category,
                   double price, int lowStockThreshold) {
        this.productId = productId;
        this.name = name;
        this.category = category;
        this.price = price;
        this.lowStockThreshold = lowStockThreshold;
    }

    public String getProductId()      { return productId; }
    public String getName()           { return name; }
    public Category getCategory()     { return category; }
    public double getPrice()          { return price; }
    public int getLowStockThreshold() { return lowStockThreshold; }

    @Override
    public String toString() {
        return String.format("Product[%s: %s, %s, $%.2f]", productId, name, category, price);
    }
}
