package filesystem;

import filesystem.exception.*;
import filesystem.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In-memory file system. Supports absolute paths only.
 *
 * Public API (matches Unix commands):
 *   mkdir(path)                — create a directory, parent must exist
 *   mkdirs(path)               — create directory and all missing parents
 *   touch(path)                — create an empty file
 *   write(path, content)       — overwrite file contents (creates if missing)
 *   append(path, content)      — append to file (creates if missing)
 *   read(path)                 — read file contents
 *   ls(path)                   — list directory children (sorted)
 *   rm(path)                   — delete a file
 *   rmdir(path)                — delete an empty directory
 *   rmRecursive(path)          — delete a directory and all its contents
 *   mv(srcPath, dstPath)       — move / rename
 *   exists(path)               — check if path exists
 *
 * Design principle (Tell, Don't Ask):
 *   - Path parsing lives on Path
 *   - Content operations live on File
 *   - Child management lives on Directory
 *   - FileSystem only orchestrates: path resolution + tree navigation
 */
public class FileSystem {
    private final Directory root;

    public FileSystem() {
        this.root = new Directory("");  // root has empty name
    }

    // ──────────────────────── Directory operations ────────────────────────

    public void mkdir(String pathString) {
        Path path = Path.of(pathString);
        if (path.isRoot()) {
            throw new PathAlreadyExistsException(pathString);  // root always exists
        }

        Directory parent = resolveDirectory(path.getParent());
        String newDirName = path.getLastSegment();

        if (parent.hasChild(newDirName)) {
            throw new PathAlreadyExistsException(pathString);
        }
        parent.addChild(new Directory(newDirName));
    }

    /** Create directory and any missing intermediate directories — like `mkdir -p`. */
    public void mkdirs(String pathString) {
        Path path = Path.of(pathString);
        Directory current = root;
        for (String segment : path.getSegments()) {
            Optional<FileSystemNode> existing = current.getChild(segment);
            if (existing.isPresent()) {
                if (!existing.get().isDirectory()) {
                    throw new NotADirectoryException(segment);
                }
                current = (Directory) existing.get();
            } else {
                Directory newDir = new Directory(segment);
                current.addChild(newDir);
                current = newDir;
            }
        }
    }

    // ──────────────────────── File operations ────────────────────────

    public void touch(String pathString) {
        createFileIfMissing(Path.of(pathString));
    }

    public void write(String pathString, String content) {
        File file = createFileIfMissing(Path.of(pathString));
        file.write(content);
    }

    public void append(String pathString, String content) {
        File file = createFileIfMissing(Path.of(pathString));
        file.append(content);
    }

    public String read(String pathString) {
        FileSystemNode node = resolveNode(Path.of(pathString));
        if (node.isDirectory()) {
            throw new NotAFileException(pathString);
        }
        return ((File) node).read();
    }

    // ──────────────────────── Listing ────────────────────────

    /**
     * List immediate children of a directory (sorted).
     * If path is a file, returns just that file's name (matches Unix `ls file.txt`).
     */
    public List<String> ls(String pathString) {
        FileSystemNode node = resolveNode(Path.of(pathString));
        if (!node.isDirectory()) {
            return List.of(node.getName());
        }
        return new ArrayList<>(((Directory) node).getChildren().keySet());
    }

    // ──────────────────────── Deletion ────────────────────────

    public void rm(String pathString) {
        Path path = Path.of(pathString);
        if (path.isRoot()) {
            throw new FileSystemException("Cannot delete root");
        }

        Directory parent = resolveDirectory(path.getParent());
        FileSystemNode node = parent.getChild(path.getLastSegment())
            .orElseThrow(() -> new PathNotFoundException(pathString));

        if (node.isDirectory()) {
            throw new NotAFileException(pathString);
        }
        parent.removeChild(path.getLastSegment());
    }

    public void rmdir(String pathString) {
        Path path = Path.of(pathString);
        if (path.isRoot()) {
            throw new FileSystemException("Cannot delete root");
        }

        Directory parent = resolveDirectory(path.getParent());
        FileSystemNode node = parent.getChild(path.getLastSegment())
            .orElseThrow(() -> new PathNotFoundException(pathString));

        if (!node.isDirectory()) {
            throw new NotADirectoryException(pathString);
        }
        Directory dir = (Directory) node;
        if (!dir.isEmpty()) {
            throw new FileSystemException(
                "Directory not empty (use rmRecursive): " + pathString);
        }
        parent.removeChild(path.getLastSegment());
    }

    public void rmRecursive(String pathString) {
        Path path = Path.of(pathString);
        if (path.isRoot()) {
            throw new FileSystemException("Cannot delete root");
        }
        Directory parent = resolveDirectory(path.getParent());
        if (!parent.hasChild(path.getLastSegment())) {
            throw new PathNotFoundException(pathString);
        }
        parent.removeChild(path.getLastSegment());
    }

    // ──────────────────────── Move / Rename ────────────────────────

    public void mv(String srcPathString, String dstPathString) {
        Path srcPath = Path.of(srcPathString);
        Path dstPath = Path.of(dstPathString);
        if (srcPath.isRoot()) {
            throw new FileSystemException("Cannot move root");
        }

        // Remove from source
        Directory srcParent = resolveDirectory(srcPath.getParent());
        FileSystemNode node = srcParent.getChild(srcPath.getLastSegment())
            .orElseThrow(() -> new PathNotFoundException(srcPathString));

        // Verify destination parent exists and destination doesn't already exist
        Directory dstParent = resolveDirectory(dstPath.getParent());
        if (dstParent.hasChild(dstPath.getLastSegment())) {
            throw new PathAlreadyExistsException(dstPathString);
        }

        srcParent.removeChild(srcPath.getLastSegment());
        // Node needs to "rename" to the new last segment.
        // Since name is final on FileSystemNode, we'd normally clone or use a builder.
        // For simplicity here: create new node with new name, re-wire children.
        FileSystemNode renamed = rename(node, dstPath.getLastSegment());
        dstParent.addChild(renamed);
    }

    public boolean exists(String pathString) {
        try {
            resolveNode(Path.of(pathString));
            return true;
        } catch (PathNotFoundException e) {
            return false;
        }
    }

    // ──────────────────────── Internal helpers ────────────────────────

    /**
     * Walk the tree from root, segment by segment.
     * Throws if any intermediate segment is missing or is a file instead of directory.
     */
    private FileSystemNode resolveNode(Path path) {
        FileSystemNode current = root;
        for (int i = 0; i < path.getSegments().size(); i++) {
            String segment = path.getSegments().get(i);
            if (!current.isDirectory()) {
                throw new NotADirectoryException(segment);
            }
            Directory currentDir = (Directory) current;
            current = currentDir.getChild(segment)
                .orElseThrow(() -> new PathNotFoundException(path.toString()));
        }
        return current;
    }

    private Directory resolveDirectory(Path path) {
        FileSystemNode node = resolveNode(path);
        if (!node.isDirectory()) {
            throw new NotADirectoryException(path.toString());
        }
        return (Directory) node;
    }

    /**
     * Create the file at the given path if missing, return existing file otherwise.
     * Throws if the node at the path is a directory.
     */
    private File createFileIfMissing(Path path) {
        if (path.isRoot()) {
            throw new FileSystemException("Cannot create file at root");
        }
        Directory parent = resolveDirectory(path.getParent());
        String fileName = path.getLastSegment();

        Optional<FileSystemNode> existing = parent.getChild(fileName);
        if (existing.isPresent()) {
            if (existing.get().isDirectory()) {
                throw new NotAFileException(path.toString());
            }
            return (File) existing.get();
        }

        File newFile = new File(fileName);
        parent.addChild(newFile);
        return newFile;
    }

    /** Rebuild a node with a new name for mv. Simple approach — clone children over. */
    private FileSystemNode rename(FileSystemNode node, String newName) {
        if (node.isDirectory()) {
            Directory oldDir = (Directory) node;
            Directory newDir = new Directory(newName);
            for (FileSystemNode child : oldDir.getChildren().values()) {
                newDir.addChild(child);  // children retain their own names
            }
            return newDir;
        } else {
            File oldFile = (File) node;
            File newFile = new File(newName);
            newFile.write(oldFile.read());
            return newFile;
        }
    }
}
