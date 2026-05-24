package instagram.model;

import java.time.Instant;

public class Comment {
    private final String commentId;
    private final String photoId;
    private final User author;
    private final String text;
    private final Instant createdAt;

    public Comment(String commentId, String photoId, User author, String text) {
        this.commentId = commentId;
        this.photoId = photoId;
        this.author = author;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public String getCommentId()  { return commentId; }
    public String getPhotoId()    { return photoId; }
    public User getAuthor()       { return author; }
    public String getText()       { return text; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() { return author + ": " + text; }
}
