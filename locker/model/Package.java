package locker.model;

import locker.enums.PackageSize;

public class Package {
    private final String packageId;
    private final String orderId;
    private final PackageSize size;
    private final double weightKg;

    public Package(String packageId, String orderId, PackageSize size, double weightKg) {
        this.packageId = packageId;
        this.orderId = orderId;
        this.size = size;
        this.weightKg = weightKg;
    }

    public String getPackageId() { return packageId; }
    public String getOrderId()   { return orderId; }
    public PackageSize getSize() { return size; }
    public double getWeightKg()  { return weightKg; }
}
