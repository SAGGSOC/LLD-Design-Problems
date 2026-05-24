package moviebooking.service;

import moviebooking.model.Cinema;
import moviebooking.model.Movie;
import moviebooking.model.Show;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SearchService {
    private final Map<String, Movie> moviesById = new ConcurrentHashMap<>();
    private final Map<String, Cinema> cinemasById = new ConcurrentHashMap<>();
    private final Map<String, Show> showsById = new ConcurrentHashMap<>();

    public void addMovie(Movie movie)      { moviesById.put(movie.getMovieId(), movie); }
    public void addCinema(Cinema cinema)   { cinemasById.put(cinema.getCinemaId(), cinema); }
    public void addShow(Show show)         { showsById.put(show.getShowId(), show); }

    public Movie getMovie(String movieId)  { return moviesById.get(movieId); }
    public Show getShow(String showId)     { return showsById.get(showId); }

    /** Find all shows of a movie in a city on a given date. */
    public List<Show> searchShows(String movieId, String city, LocalDate date) {
        return showsById.values().stream()
            .filter(show -> show.getMovie().getMovieId().equals(movieId))
            .filter(show -> {
                String showCity = findCinemaForScreen(show.getScreen().getCinemaId())
                    .getCity();
                return showCity.equalsIgnoreCase(city);
            })
            .filter(show -> {
                LocalDate showDate = show.getStartTime().atZone(ZoneId.systemDefault())
                    .toLocalDate();
                return showDate.equals(date);
            })
            .sorted(Comparator.comparing(Show::getStartTime))
            .collect(Collectors.toList());
    }

    public List<Movie> listMoviesInCity(String city) {
        Set<String> movieIds = new HashSet<>();
        for (Show show : showsById.values()) {
            String showCity = findCinemaForScreen(show.getScreen().getCinemaId()).getCity();
            if (showCity.equalsIgnoreCase(city)) {
                movieIds.add(show.getMovie().getMovieId());
            }
        }
        return movieIds.stream().map(moviesById::get).collect(Collectors.toList());
    }

    private Cinema findCinemaForScreen(String cinemaId) {
        return cinemasById.get(cinemaId);
    }
}
