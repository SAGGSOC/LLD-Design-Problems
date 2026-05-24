package parkinglot;

import parkinglot.enums.SpotType;
import parkinglot.factory.ParkingSpotFactory;
import parkinglot.model.ParkingFloor;
import parkinglot.model.ParkingLot;
import parkinglot.model.ParkingSpot;

import java.util.ArrayList;
import java.util.List;

public class ParkingLotBuilder {

    public static ParkingLot buildDefaultLot() {
        List<ParkingFloor> floors = new ArrayList<>();

        for (int floorNum = 1; floorNum <= 5; floorNum++) {
            List<ParkingSpot> spots = new ArrayList<>();
            int spotNum = 1;

            // 10 motorcycle spots (SMALL)
            for (int i = 0; i < 10; i++) {
                String spotId = "F" + floorNum + "-S" + spotNum;
                spots.add(ParkingSpotFactory.createSpot(
                        SpotType.SMALL, spotId, floorNum, spotNum++, false, false));
            }

            // 30 car spots (MEDIUM) — 2 handicap, 3 EV
            for (int i = 0; i < 30; i++) {
                String spotId = "F" + floorNum + "-S" + spotNum;
                boolean isHandicap = (i < 2);
                boolean hasEVCharging = (i >= 2 && i < 5);
                spots.add(ParkingSpotFactory.createSpot(
                        SpotType.MEDIUM, spotId, floorNum, spotNum++, isHandicap, hasEVCharging));
            }

            // 10 large spots (LARGE — trucks, buses)
            for (int i = 0; i < 10; i++) {
                String spotId = "F" + floorNum + "-S" + spotNum;
                spots.add(ParkingSpotFactory.createSpot(
                        SpotType.LARGE, spotId, floorNum, spotNum++, false, false));
            }

            floors.add(new ParkingFloor(floorNum, spots));
        }

        return new ParkingLot("Downtown Parking", floors);
    }
}
