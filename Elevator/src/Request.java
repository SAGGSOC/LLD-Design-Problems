import java.util.Objects;

public class Request {
    private final int floor;
    private final RequestType requestType;

    public Request(int floor, RequestType requestType) {
        this.floor = floor;
        this.requestType = requestType;
    }

    public int getFloor() { return floor; }
    public RequestType getRequestType() { return requestType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Request)) return false;
        Request request = (Request) o;
        return floor == request.floor && requestType == request.requestType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(floor, requestType);
    }

    @Override
    public String toString() {
        return "Request{floor=" + floor + ", type=" + requestType + "}";
    }
}
