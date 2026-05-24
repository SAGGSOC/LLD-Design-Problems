package filesystem.exception;

public class PathNotFoundException extends FileSystemException {
    public PathNotFoundException(String path) {
        super("Path not found: " + path);
    }
}
