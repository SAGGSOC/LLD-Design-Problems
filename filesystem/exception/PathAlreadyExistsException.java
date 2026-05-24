package filesystem.exception;

public class PathAlreadyExistsException extends FileSystemException {
    public PathAlreadyExistsException(String path) {
        super("Path already exists: " + path);
    }
}
