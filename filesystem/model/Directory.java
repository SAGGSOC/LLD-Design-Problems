package filesystem.model;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A directory contains a map of child name → FileSystemNode.
 * Uses TreeMap so list operations return children in sorted order (matches `ls` behavior).
 */
public class Directory extends FileSystemNode {
    // TreeMap → sorted iteration, matches `ls` output. Use ConcurrentHashMap for thread safety.
    private final Map<String, FileSystemNode> children = new TreeMap<>();

    public Directory(String name) {
        super(name);
    }

    @Override
    public boolean isDirectory() { return true; }

    @Override
    public long getSize() {
        // Recursive — sum up all descendants
        return children.values().stream().mapToLong(FileSystemNode::getSize).sum();
    }

    public void addChild(FileSystemNode node) {
        children.put(node.getName(), node);
        touch();
    }

    public FileSystemNode removeChild(String name) {
        FileSystemNode removed = children.remove(name);
        if (removed != null) touch();
        return removed;
    }

    public Optional<FileSystemNode> getChild(String name) {
        return Optional.ofNullable(children.get(name));
    }

    public boolean hasChild(String name) {
        return children.containsKey(name);
    }

    public Map<String, FileSystemNode> getChildren() {
        return children;  // TreeMap already sorted
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }
}
