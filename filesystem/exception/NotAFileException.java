package filesystem.exception;

public class NotAFileException extends FileSystemException {
    public NotAFileException(String path) {
        super("Not a file: " + path);
    }
}
