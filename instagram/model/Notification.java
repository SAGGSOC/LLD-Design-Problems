package instagram.model;

import instagram.enums.NotificationType;

import java.time.Instant;

public class Notification {
    private final String notificationId;
    private final String recipientUserId;
    private final User actor;           // who performed the action
    private final NotificationType type;
    private final String photoId;       // relevant photo (null for follow notifications)
    private final String message;
    private final Instant createdAt;
    private boolean read;

    public Notification(String notificationId, String recipientUserId, User actor,
                        NotificationType type, String photoId, String message) {
        this.notificationId = notificationId;
        this.recipientUserId = recipientUserId;
        this.actor = actor;
        this.type = type;
        this.photoId = photoId;
        this.message = message;
        this.createdAt = Instant.now();
        this.read = false;
    }

    public void markRead() { this.read = true; }

    public String getNotificationId()   { return notificationId; }
    public String getRecipientUserId()  { return recipientUserId; }
    public User getActor()              { return actor; }
    public NotificationType getType()   { return type; }
    public String getPhotoId()          { return photoId; }
    public String getMessage()          { return message; }
    public Instant getCreatedAt()       { return createdAt; }
    public boolean isRead()             { return read; }

    @Override
    public String toString() {
        return "[" + type + "] " + message + (read ? " (read)" : "");
    }
}
