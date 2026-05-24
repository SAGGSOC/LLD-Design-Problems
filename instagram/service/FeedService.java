package instagram.service;

import instagram.model.Photo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a user's feed — photos from people they follow, newest first.
 *
 * Pull model (fan-out on read):
 *   On feed request, collect photos from each followee and merge/sort.
 *   Simple, always fresh, no write amplification, but O(N × M) per read
 *   where N = followees, M = photos per followee.
 *
 * Push model (fan-out on write):
 *   On new post, write the photoId into each follower's feed list.
 *   O(F) writes where F = follower count, O(1) reads. Great for read-heavy
 *   workloads, but "celebrity" users (millions of followers) cause write storms.
 *
 * Real Instagram uses a HYBRID:
 *   - Pull for celebrities (avoid fan-out to millions on each post)
 *   - Push for regular users (cheaper reads)
 *
 * For this interview-scope implementation, we use pull for simplicity.
 */
public class FeedService {
    private final FollowService followService;
    private final PhotoService photoService;

    public FeedService(FollowService followService, PhotoService photoService) {
        this.followService = followService;
        this.photoService = photoService;
    }

    /**
     * Get the feed for a user: all photos from people they follow, newest first.
     * @param limit  max photos to return (pagination — use offset for pages)
     */
    public List<Photo> getFeed(String userId, int limit) {
        Set<String> following = followService.getFollowing(userId);
        if (following.isEmpty()) return Collections.emptyList();

        List<Photo> allPhotos = new ArrayList<>();
        for (String followeeId : following) {
            allPhotos.addAll(photoService.getUserPhotos(followeeId));
        }

        return allPhotos.stream()
            .sorted(Comparator.comparing(Photo::getCreatedAt).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    public List<Photo> getFeed(String userId) {
        return getFeed(userId, 20);  // default page size
    }
}
