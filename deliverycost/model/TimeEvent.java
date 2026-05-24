package deliverycost.model;

/**
 * Represents a sweep-line event for cost calculation.
 * +1 at delivery start, -1 at delivery end.
 */
public class TimeEvent implements Comparable<TimeEvent> {
    private final int time;
    private final int delta; // +1 for start, -1 for end

    public TimeEvent(int time, int delta) {
        this.time = time;
        this.delta = delta;
    }

    public int getTime() { return time; }
    public int getDelta() { return delta; }

    @Override
    public int compareTo(TimeEvent other) {
        // Sort by time; if tie, process ends before starts
        if (this.time != other.time) return Integer.compare(this.time, other.time);
        return Integer.compare(this.delta, other.delta);
    }
}
