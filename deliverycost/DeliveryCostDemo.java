package deliverycost;

import deliverycost.service.DeliveryCostService;

public class DeliveryCostDemo {

    public static void main(String[] args) {
        DeliveryCostService service = new DeliveryCostService();

        // Register drivers
        service.addDriver("D1");
        service.addDriver("D2");
        service.addDriver("D3");

        // ═══════════════════════════════════════════════
        // PART 1: Cost Calculation
        // ═══════════════════════════════════════════════
        System.out.println("═══ PART 1: Cost Calculation ═══");

        service.addDelivery("D1", 0, 60);    // D1: 0-60 min
        service.addDelivery("D2", 30, 90);   // D2: 30-90 min (overlaps D1)
        service.addDelivery("D3", 120, 180); // D3: 120-180 min (no overlap)

        System.out.println("Total Cost: $" + service.getTotalCost());
        // Expected: $17.00

        // ═══════════════════════════════════════════════
        // PART 2: Payment Tracking
        // ═══════════════════════════════════════════════
        System.out.println("\n═══ PART 2: Payment Tracking ═══");

        // Pay for everything up to minute 60
        double paid1 = service.payUpToTime(60);
        System.out.println("Paid up to t=60: $" + paid1);
        // Covers: D1[0,60) fully, D2[30,60) partially
        // [0,30): 1 driver → 0.5h * $5 = $2.50
        // [30,60): 2 drivers → 0.5h * (2*5 + 2*1*2) = $7.00
        // Total paid = $9.50

        System.out.println("Remaining to pay: $" + service.getCostToBePaid());
        // Remaining: D2[60,90) + D3[120,180)
        // [60,90): 1 driver → 0.5h * $5 = $2.50
        // [120,180): 1 driver → 1h * $5 = $5.00
        // Remaining = $7.50

        // Pay for everything up to minute 100
        double paid2 = service.payUpToTime(100);
        System.out.println("Paid up to t=100: $" + paid2);
        // Covers D2[60,90) → 0.5h * $5 = $2.50

        System.out.println("Remaining to pay: $" + service.getCostToBePaid());
        // Remaining: D3[120,180) → $5.00

        // ═══════════════════════════════════════════════
        // PART 3: Analytics
        // ═══════════════════════════════════════════════
        System.out.println("\n═══ PART 3: Analytics ═══");

        // At time 90, last 24h window is [-1350, 90]
        // Active deliveries in that window: D1[0,60), D2[30,90)
        // Max concurrent = 2 (during [30,60))
        int maxAt90 = service.getMaxActiveDriversInLast24Hours(90);
        System.out.println("Max active drivers (last 24h from t=90): " + maxAt90);

        // At time 180, all 3 deliveries are in window
        // Max concurrent = 2 (D1 and D2 overlap at [30,60))
        int maxAt180 = service.getMaxActiveDriversInLast24Hours(180);
        System.out.println("Max active drivers (last 24h from t=180): " + maxAt180);

        // Add more overlapping deliveries to test higher concurrency
        service.addDelivery("D1", 200, 260);
        service.addDelivery("D2", 210, 270);
        service.addDelivery("D3", 220, 280);

        // At time 280, all 3 new deliveries overlap at [220,260)
        int maxAt280 = service.getMaxActiveDriversInLast24Hours(280);
        System.out.println("Max active drivers (last 24h from t=280): " + maxAt280);
        // Expected: 3
    }
}
