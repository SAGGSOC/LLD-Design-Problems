package filesystem.model;

public class File extends FileSystemNode {
    private StringBuilder content;

    public File(String name) {
        super(name);
        this.content = new StringBuilder();
    }

    @Override
    public boolean isDirectory() { return false; }

    @Override
    public long getSize() { return content.length(); }

    public String read() {
        return content.toString();
    }

    /** Overwrite all existing content. */
    public void write(String data) {
        this.content = new StringBuilder(data);
        touch();
    }

    /** Append to existing content. */
    public void append(String data) {
        this.content.append(data);
        touch();
    }
}
