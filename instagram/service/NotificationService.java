package instagram.service;

import instagram.enums.NotificationType;
import instagram.model.Notification;
import instagram.model.User;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class NotificationService {
    private final Map<String, List<Notification>> inboxByUserId = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    public void notifyLike(String recipientUserId, User liker, String photoId) {
        push(recipientUserId, new Notification(
            newId(), recipientUserId, liker, NotificationType.LIKE,
            photoId, liker.getUsername() + " liked your photo"));
    }

    public void notifyComment(String recipientUserId, User commenter, String photoId, String text) {
        push(recipientUserId, new Notification(
            newId(), recipientUserId, commenter, NotificationType.COMMENT,
            photoId, commenter.getUsername() + " commented: " + text));
    }

    public void notifyFollow(String recipientUserId, User follower) {
        push(recipientUserId, new Notification(
            newId(), recipientUserId, follower, NotificationType.FOLLOW,
            null, follower.getUsername() + " started following you"));
    }

    public List<Notification> getInbox(String userId) {
        return new ArrayList<>(inboxByUserId.getOrDefault(userId, Collections.emptyList()));
    }

    public int getUnreadCount(String userId) {
        return (int) inboxByUserId.getOrDefault(userId, Collections.emptyList()).stream()
            .filter(n -> !n.isRead()).count();
    }

    public void markAllRead(String userId) {
        List<Notification> inbox = inboxByUserId.get(userId);
        if (inbox != null) inbox.forEach(Notification::markRead);
    }

    private void push(String recipientUserId, Notification notification) {
        inboxByUserId.computeIfAbsent(recipientUserId, k -> new CopyOnWriteArrayList<>())
            .add(0, notification);  // newest first
    }

    private String newId() { return "N-" + idCounter.getAndIncrement(); }
}
