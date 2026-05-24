package hotel.service;

import hotel.model.Guest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GuestService {
    private final Map<String, Guest> guestsById = new ConcurrentHashMap<>();

    public Guest registerGuest(String name, String email, String phone, String idProof) {
        String guestId = "GST-" + (guestsById.size() + 1);
        Guest guest = new Guest(guestId, name, email, phone, idProof);
        guestsById.put(guestId, guest);
        return guest;
    }

    public Guest getGuest(String guestId) {
        Guest guest = guestsById.get(guestId);
        if (guest == null) throw new IllegalArgumentException("Guest not found: " + guestId);
        return guest;
    }
}
