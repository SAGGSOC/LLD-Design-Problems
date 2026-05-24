package filesystem.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable path representation — parses "/a/b/c.txt" into segments ["a", "b", "c.txt"].
 * Only supports absolute paths (must start with "/").
 *
 * Keeps path parsing out of the FileSystem class for single-responsibility.
 */
public class Path {
    private final List<String> segments;
    private final String original;

    private Path(String original, List<String> segments) {
        this.original = original;
        this.segments = Collections.unmodifiableList(segments);
    }

    /** Parse a path like "/a/b/c" into a Path object. */
    public static Path of(String pathString) {
        if (pathString == null || !pathString.startsWith("/")) {
            throw new IllegalArgumentException("Path must be absolute: " + pathString);
        }

        List<String> parsed = new ArrayList<>();
        if (pathString.equals("/")) {
            return new Path(pathString, parsed);  // root has empty segments
        }

        String[] parts = pathString.split("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;  // handles leading "/" and "//" collapses
            if (part.contains("/") || part.contains(" ")) {
                throw new IllegalArgumentException("Invalid path segment: " + part);
            }
            parsed.add(part);
        }
        return new Path(pathString, parsed);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public List<String> getSegments() {
        return segments;
    }

    /** The last segment, e.g. "c.txt" for "/a/b/c.txt". Throws if path is root. */
    public String getLastSegment() {
        if (isRoot()) throw new IllegalStateException("Root has no last segment");
        return segments.get(segments.size() - 1);
    }

    /** All segments except the last — the parent path. */
    public Path getParent() {
        if (isRoot()) throw new IllegalStateException("Root has no parent");
        List<String> parentSegments = new ArrayList<>(segments.subList(0, segments.size() - 1));
        String parentString = parentSegments.isEmpty() ? "/"
            : "/" + String.join("/", parentSegments);
        return new Path(parentString, parentSegments);
    }

    @Override
    public String toString() { return original; }
}
