package instagram.service;

import instagram.exception.PhotoNotFoundException;
import instagram.model.Comment;
import instagram.model.Photo;
import instagram.model.User;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class PhotoService {
    private final Map<String, Photo> photosById = new ConcurrentHashMap<>();
    private final Map<String, List<String>> photosByAuthorId = new ConcurrentHashMap<>();
    private final AtomicLong photoIdCounter = new AtomicLong(1);
    private final AtomicLong commentIdCounter = new AtomicLong(1);

    private final NotificationService notificationService;

    public PhotoService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public Photo postPhoto(User author, String imageUrl, String caption) {
        String photoId = "P-" + photoIdCounter.getAndIncrement();
        Photo photo = new Photo(photoId, author, imageUrl, caption);
        photosById.put(photoId, photo);
        photosByAuthorId
            .computeIfAbsent(author.getUserId(), k -> new CopyOnWriteArrayList<>())
            .add(photoId);
        return photo;
    }

    public Photo getPhoto(String photoId) {
        Photo photo = photosById.get(photoId);
        if (photo == null) throw new PhotoNotFoundException(photoId);
        return photo;
    }

    /** Returns true if this created a new like (not already liked). */
    public boolean likePhoto(String photoId, User liker) {
        Photo photo = getPhoto(photoId);
        boolean added = photo.like(liker.getUserId());
        // Only notify on actual new like, and not when liking your own photo
        if (added && !photo.getAuthor().getUserId().equals(liker.getUserId())) {
            notificationService.notifyLike(photo.getAuthor().getUserId(), liker, photoId);
        }
        return added;
    }

    public boolean unlikePhoto(String photoId, User unliker) {
        return getPhoto(photoId).unlike(unliker.getUserId());
    }

    public Comment addComment(String photoId, User commenter, String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Comment text cannot be empty");
        }
        Photo photo = getPhoto(photoId);
        String commentId = "C-" + commentIdCounter.getAndIncrement();
        Comment comment = new Comment(commentId, photoId, commenter, text);
        photo.addComment(comment);

        // Notify photo author (but not if self-commenting)
        if (!photo.getAuthor().getUserId().equals(commenter.getUserId())) {
            notificationService.notifyComment(
                photo.getAuthor().getUserId(), commenter, photoId, text);
        }
        return comment;
    }

    public List<Photo> getUserPhotos(String userId) {
        List<String> photoIds = photosByAuthorId.getOrDefault(userId, Collections.emptyList());
        List<Photo> photos = new ArrayList<>();
        for (String id : photoIds) {
            Photo p = photosById.get(id);
            if (p != null) photos.add(p);
        }
        // Most recent first
        photos.sort(Comparator.comparing(Photo::getCreatedAt).reversed());
        return photos;
    }
}
