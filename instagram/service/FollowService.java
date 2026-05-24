package instagram.service;

import instagram.exception.UserNotFoundException;
import instagram.model.User;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Social graph — directional follow relationships.
 *
 * Two adjacency maps for O(1) reads in both directions:
 *   following[A]  = set of users A follows
 *   followers[A] = set of users who follow A
 *
 * Both updated atomically on follow/unfollow. This double-storage
 * trade-off is worth it: the feed query needs "who do I follow" (pull model),
 * while the profile view needs "who follows me" (for follower count).
 *
 * For production scale (billions of users), this moves to a graph DB (Neo4j)
 * or a sharded key-value store keyed by userId.
 */
public class FollowService {
    private final Map<String, Set<String>> followingByUserId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> followersByUserId = new ConcurrentHashMap<>();

    private final UserService userService;
    private final NotificationService notificationService;

    public FollowService(UserService userService, NotificationService notificationService) {
        this.userService = userService;
        this.notificationService = notificationService;
    }

    /** Returns true if this call created a new follow (not already following). */
    public synchronized boolean follow(String followerUserId, String followeeUserId) {
        if (followerUserId.equals(followeeUserId)) {
            throw new IllegalArgumentException("Cannot follow yourself");
        }
        User follower = userService.getUser(followerUserId);
        User followee = userService.getUser(followeeUserId);

        Set<String> myFollowing = followingByUserId
            .computeIfAbsent(followerUserId, k -> ConcurrentHashMap.newKeySet());
        Set<String> theirFollowers = followersByUserId
            .computeIfAbsent(followeeUserId, k -> ConcurrentHashMap.newKeySet());

        if (!myFollowing.add(followeeUserId)) {
            return false;   // already following
        }
        theirFollowers.add(followerUserId);

        notificationService.notifyFollow(followeeUserId, follower);
        return true;
    }

    public synchronized boolean unfollow(String followerUserId, String followeeUserId) {
        Set<String> myFollowing = followingByUserId.get(followerUserId);
        Set<String> theirFollowers = followersByUserId.get(followeeUserId);
        if (myFollowing == null || !myFollowing.remove(followeeUserId)) {
            return false;   // wasn't following
        }
        if (theirFollowers != null) theirFollowers.remove(followerUserId);
        return true;
    }

    public boolean isFollowing(String followerUserId, String followeeUserId) {
        Set<String> myFollowing = followingByUserId.get(followerUserId);
        return myFollowing != null && myFollowing.contains(followeeUserId);
    }

    public Set<String> getFollowing(String userId) {
        return Collections.unmodifiableSet(
            followingByUserId.getOrDefault(userId, Collections.emptySet()));
    }

    public Set<String> getFollowers(String userId) {
        return Collections.unmodifiableSet(
            followersByUserId.getOrDefault(userId, Collections.emptySet()));
    }

    public int getFollowingCount(String userId) {
        return followingByUserId.getOrDefault(userId, Collections.emptySet()).size();
    }

    public int getFollowerCount(String userId) {
        return followersByUserId.getOrDefault(userId, Collections.emptySet()).size();
    }
}
