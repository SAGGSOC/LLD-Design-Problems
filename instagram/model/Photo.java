package instagram.model;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A photo post. The aggregate owns its own likes and comments — Tell, Don't Ask.
 *
 * Likes are a Set<String> (userId) — one like per user per photo, O(1) toggle.
 * Comments are a CopyOnWriteArrayList — concurrent-safe append, iterate without locks.
 */
public class Photo {
    private final String photoId;
    private final User author;
    private final String imageUrl;
    private final String caption;
    private final Instant createdAt;

    // Who liked this photo. Set prevents duplicate likes automatically.
    private final Set<String> likedByUserIds = Collections.synchronizedSet(new LinkedHashSet<>());

    // Comments in order of posting
    private final List<Comment> comments = new CopyOnWriteArrayList<>();

    public Photo(String photoId, User author, String imageUrl, String caption) {
        this.photoId = photoId;
        this.author = author;
        this.imageUrl = imageUrl;
        this.caption = caption;
        this.createdAt = Instant.now();
    }

    /** Returns true if this call added a like, false if user already liked. */
    public boolean like(String userId) {
        return likedByUserIds.add(userId);
    }

    /** Returns true if this call removed a like, false if no prior like. */
    public boolean unlike(String userId) {
        return likedByUserIds.remove(userId);
    }

    public boolean isLikedBy(String userId) {
        return likedByUserIds.contains(userId);
    }

    public int getLikeCount() {
        return likedByUserIds.size();
    }

    public void addComment(Comment comment) {
        comments.add(comment);
    }

    public List<Comment> getComments() {
        return Collections.unmodifiableList(comments);
    }

    public int getCommentCount() {
        return comments.size();
    }

    public String getPhotoId()      { return photoId; }
    public User getAuthor()         { return author; }
    public String getImageUrl()     { return imageUrl; }
    public String getCaption()      { return caption; }
    public Instant getCreatedAt()   { return createdAt; }
}
