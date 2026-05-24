package filesystem.exception;

public class NotADirectoryException extends FileSystemException {
    public NotADirectoryException(String path) {
        super("Not a directory: " + path);
    }
}
