package moviebooking.model;

import java.time.Duration;
import java.util.List;

public class Movie {
    private final String movieId;
    private final String title;
    private final String language;
    private final List<String> genres;
    private final Duration duration;
    private final String rating;  // e.g. "PG-13"
    private final double imdbRating;

    public Movie(String movieId, String title, String language,
                 List<String> genres, Duration duration, String rating, double imdbRating) {
        this.movieId = movieId;
        this.title = title;
        this.language = language;
        this.genres = genres;
        this.duration = duration;
        this.rating = rating;
        this.imdbRating = imdbRating;
    }

    public String getMovieId()       { return movieId; }
    public String getTitle()         { return title; }
    public String getLanguage()      { return language; }
    public List<String> getGenres()  { return genres; }
    public Duration getDuration()    { return duration; }
    public String getRating()        { return rating; }
    public double getImdbRating()    { return imdbRating; }
}
