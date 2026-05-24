import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import BookMyShow.SeatStatus;
import airline.enums.PaymentStatus;
import airline.model.Payment;
import moviebooking.service.BookingService;

public class BookingController{
    private final BookingService bookingService;
    public BookingController(BookingService bookingService){
        this.bookingService = bookingService;
    }
    public Booking createBooking(BookingRequest bookingRequest){
        return bookingService.createBooking(bookingRequest);
    }
    public Booking getBooking(UUID bookingId){
        return bookingService.getBooking(bookingId);
    }
    public List<Booking> getBookingsForUser(UUID userId){
        return bookingService.getBookingsForUser(userId);
    }
}
public class TheatreController{
    private final TheatreService theatreService;
    
}
public class Booking{
    private final UUID bookingId;
    private final User user;
    private final Show show;
    private final List<Integer> seats;
    private final Payment payment;

    public Booking(User user,Show show, List<Integer> seats, Payment payment){
        this.bookingId = UUID.randomUUID();
        this.user = user;
        this.show = show;
        this.seats = seats;
        this.payment = payment;
    }
    public UUID getBookingId() {
        return bookingId;
    }
    public User getUser(){
        return user;
    }
    public Payment getPayment() {
        return payment;
    }
}
public class Movie{
     private final String name;
     public Movie(String name){
        this.name = name;
     }
     public String getName(){
        return name;
     }
}
public class Payment{
    private final UUID paymentId;
    private final PaymentStatus status;
    public Payment(PaymentStatus status){
        this.paymentId = UUID.randomUUID();
        this.status = status;
    }
    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getStatus() {
        return status;
    }
}
public class Screen{
    private final int screenId;
    private final List<Seat> seats;
    private final Map<LocalDate, List<Show>> showsByDate = new HashMap<>();
    public Screen(int screenId, List<Seat> seats){
        this.screenId = screenId;
        this.seats = seats;
    }
    public List<Seat> getSeats(){
        return seats;
    }
    public void addShow(Show show){
        showsByDate.computeIfAbsent(show.getShowDate(), d->new ArrayList<>()).add(show);
    }
    public List<Show> getShows(LocalDate date){
        return showsByDate.getOrDefault(date, new ArrayList<>());
    }
}
public class Seat{
    private  final  int seatId;
    private final SeatCategory category;
    public Seat(int seatId, SeatCategory category) {
        this.seatId = seatId;
        this.category = category;
    }

    public int getSeatId() {
        return seatId;
    }

}
public class Show{
    private final Movie movie;
    private final LocalDate showDate;
    private final LocalTime startTime;
    private final Map<Integer, SeatStatus> seatStatusMap = new HashMap<>();
    private final Map<Integer, ReentrantLock> seatLocks = new HashMap<>();

    public Show(Movie movie, Screen screen, LocalDate date, LocalTime time){
        this.movie = movie;
        this.showDate = date;
        this.startTime = time;

        for(Seat seat: screen.getSeats()){
            seatStatusMap.put(seat.getSeatId(), SeatStatus.AVAILABLE);
            seatLocks.put(seat.getSeatId(), new ReentrantLock());
        }
    }
    public Movie getMovie(){
        return movie;
    }
    public LocalDate getShowDate(){
        return showDate;
    }
    public LocalTime getStartTime() {
        return startTime;
    }
    public boolean isSeatAvailable(int seatId){
        return seatStatusMap.get(seatId) == SeatStatus.AVAILABLE;
    }
    public boolean lockSeats(List<Integer> seatIds){
        List<Integer> sorted =  new ArrayList<>(seatIds);
        Collections.sort(sorted);
        List<ReentrantLock> acquiredLocks =  new ArrayList<>();
        try{
            for(int seatId : sorted){
                ReentrantLock lock = seatLocks.get(seatId);
                lock.lock();
                acquiredLocks.add(lock);
            }
            for(int seatid : sorted){
                if (seatStatusMap.get(seatId) != SeatStatus.AVAILABLE) {
                    return false;
                }
            }
            for (int seatId : sorted) {
                seatStatusMap.put(seatId, SeatStatus.LOCKED);
            }

            return true;
        } finally {
            // Phase 4: release locks
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
        }

    }
    public void confirmSeats(List<Integer> seatIds) {
        for (int seatId : seatIds) {
            seatStatusMap.put(seatId, SeatStatus.BOOKED);
        }
    }

    public void releaseSeats(List<Integer> seatIds) {
        for (int seatId : seatIds) {
            seatStatusMap.put(seatId, SeatStatus.AVAILABLE);
        }
    }

}
public class 
public class BookMyShowv2 {
    
}
