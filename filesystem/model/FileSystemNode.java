package filesystem.model;

import java.time.Instant;

/**
 * Abstract base for files and directories.
 * Encapsulates common metadata: name, created/modified timestamps.
 *
 * This is the Composite Pattern base — Directory can contain any FileSystemNode,
 * allowing uniform treatment of both files and directories in tree traversals.
 */
public abstract class FileSystemNode {
    protected final String name;
    protected final Instant createdAt;
    protected Instant modifiedAt;

    protected FileSystemNode(String name) {
        this.name = name;
        this.createdAt = Instant.now();
        this.modifiedAt = this.createdAt;
    }

    /** True if this node is a directory (can contain children). */
    public abstract boolean isDirectory();

    /** Size in bytes — files return content length, directories return sum of children. */
    public abstract long getSize();

    public String getName()         { return name; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getModifiedAt()  { return modifiedAt; }

    protected void touch() { this.modifiedAt = Instant.now(); }
}
