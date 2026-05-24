package instagram;

import instagram.model.Comment;
import instagram.model.Notification;
import instagram.model.Photo;
import instagram.model.User;
import instagram.service.*;

import java.util.List;

public class InstagramDemo {

    public static void main(String[] args) {
        // ─── Setup ───
        UserService userService = new UserService();
        NotificationService notificationService = new NotificationService();
        FollowService followService = new FollowService(userService, notificationService);
        PhotoService photoService = new PhotoService(notificationService);
        FeedService feedService = new FeedService(followService, photoService);

        // ─── Scenario 1: Register users ───
        System.out.println("=== Scenario 1: Register users ===");
        User alice = userService.registerUser("alice", "Love coffee and code", "alice.jpg");
        User bob = userService.registerUser("bob", "Traveler", "bob.jpg");
        User charlie = userService.registerUser("charlie", "Photographer", "charlie.jpg");
        User dave = userService.registerUser("dave", "Foodie", "dave.jpg");
        System.out.println("Registered: " + alice + ", " + bob + ", " + charlie + ", " + dave);
        System.out.println();

        // ─── Scenario 2: Follow/unfollow ───
        System.out.println("=== Scenario 2: Follow graph ===");
        followService.follow(alice.getUserId(), bob.getUserId());
        followService.follow(alice.getUserId(), charlie.getUserId());
        followService.follow(bob.getUserId(), charlie.getUserId());
        followService.follow(dave.getUserId(), charlie.getUserId());
        followService.follow(charlie.getUserId(), alice.getUserId());

        System.out.println("alice follows: " + usernames(followService.getFollowing(alice.getUserId()), userService));
        System.out.println("charlie's followers: " + usernames(followService.getFollowers(charlie.getUserId()), userService));
        System.out.println("charlie follower count: " + followService.getFollowerCount(charlie.getUserId()));
        System.out.println();

        // ─── Scenario 3: Duplicate follow is idempotent ───
        System.out.println("=== Scenario 3: Duplicate follow ===");
        boolean firstFollow = followService.follow(alice.getUserId(), bob.getUserId());
        System.out.println("alice follows bob (already following): " + firstFollow
            + " — expected false");
        System.out.println();

        // ─── Scenario 4: Post photos ───
        System.out.println("=== Scenario 4: Post photos ===");
        Photo bobPhoto1 = photoService.postPhoto(bob, "https://cdn/bob1.jpg", "Beach day!");
        sleep(10);  // ensure distinct timestamps
        Photo charliePhoto = photoService.postPhoto(charlie, "https://cdn/c1.jpg", "Sunset");
        sleep(10);
        Photo bobPhoto2 = photoService.postPhoto(bob, "https://cdn/bob2.jpg", "Dinner");
        sleep(10);
        Photo alicePhoto = photoService.postPhoto(alice, "https://cdn/a1.jpg", "New laptop");
        System.out.println("Posted 4 photos across 3 users");
        System.out.println();

        // ─── Scenario 5: Like photos ───
        System.out.println("=== Scenario 5: Likes ===");
        photoService.likePhoto(charliePhoto.getPhotoId(), alice);
        photoService.likePhoto(charliePhoto.getPhotoId(), bob);
        photoService.likePhoto(charliePhoto.getPhotoId(), dave);
        // Double-like is idempotent
        boolean secondLike = photoService.likePhoto(charliePhoto.getPhotoId(), alice);
        System.out.println("alice second-like returns: " + secondLike + " — expected false");
        System.out.println("charlie's sunset photo likes: " + charliePhoto.getLikeCount());
        System.out.println("  alice liked it? " + charliePhoto.isLikedBy(alice.getUserId()));
        System.out.println();

        // ─── Scenario 6: Comments ───
        System.out.println("=== Scenario 6: Comments ===");
        Comment c1 = photoService.addComment(charliePhoto.getPhotoId(), alice, "Stunning!");
        Comment c2 = photoService.addComment(charliePhoto.getPhotoId(), bob, "Where is this?");
        System.out.println("Comments on sunset:");
        for (Comment c : charliePhoto.getComments()) {
            System.out.println("  " + c);
        }
        System.out.println();

        // ─── Scenario 7: Feed — alice sees photos from people she follows ───
        System.out.println("=== Scenario 7: alice's feed ===");
        List<Photo> aliceFeed = feedService.getFeed(alice.getUserId());
        for (Photo p : aliceFeed) {
            System.out.println("  @" + p.getAuthor().getUsername() + " — " + p.getCaption()
                + "  [" + p.getLikeCount() + " likes, "
                + p.getCommentCount() + " comments]");
        }
        // alice follows bob and charlie — should see 3 photos (bob ×2, charlie ×1)
        // Own photo (alice's) should NOT appear
        System.out.println();

        // ─── Scenario 8: Dave's feed ───
        System.out.println("=== Scenario 8: dave's feed (only follows charlie) ===");
        List<Photo> daveFeed = feedService.getFeed(dave.getUserId());
        for (Photo p : daveFeed) {
            System.out.println("  @" + p.getAuthor().getUsername() + " — " + p.getCaption());
        }
        System.out.println();

        // ─── Scenario 9: Notifications ───
        System.out.println("=== Scenario 9: charlie's notifications ===");
        List<Notification> charlieInbox = notificationService.getInbox(charlie.getUserId());
        System.out.println("Unread: " + notificationService.getUnreadCount(charlie.getUserId()));
        for (Notification n : charlieInbox) {
            System.out.println("  " + n);
        }
        notificationService.markAllRead(charlie.getUserId());
        System.out.println("After markAllRead, unread: "
            + notificationService.getUnreadCount(charlie.getUserId()));
        System.out.println();

        // ─── Scenario 10: Unfollow and verify feed updates ───
        System.out.println("=== Scenario 10: alice unfollows bob ===");
        followService.unfollow(alice.getUserId(), bob.getUserId());
        List<Photo> aliceFeedAfter = feedService.getFeed(alice.getUserId());
        System.out.println("alice's feed after unfollowing bob:");
        for (Photo p : aliceFeedAfter) {
            System.out.println("  @" + p.getAuthor().getUsername() + " — " + p.getCaption());
        }
        System.out.println();

        // ─── Scenario 11: Error handling ───
        System.out.println("=== Scenario 11: Error cases ===");
        try {
            followService.follow(alice.getUserId(), alice.getUserId());
            System.out.println("  [FAIL] self-follow allowed");
        } catch (IllegalArgumentException e) {
            System.out.println("  [OK]   self-follow rejected: " + e.getMessage());
        }
        try {
            photoService.addComment(bobPhoto1.getPhotoId(), alice, "   ");
            System.out.println("  [FAIL] empty comment allowed");
        } catch (IllegalArgumentException e) {
            System.out.println("  [OK]   empty comment rejected");
        }
        try {
            userService.registerUser("alice", "dup", "");
            System.out.println("  [FAIL] duplicate username allowed");
        } catch (IllegalArgumentException e) {
            System.out.println("  [OK]   duplicate username rejected: " + e.getMessage());
        }
    }

    private static String usernames(java.util.Set<String> userIds, UserService userService) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String id : userIds) {
            if (!first) sb.append(", ");
            sb.append("@").append(userService.getUser(id).getUsername());
            first = false;
        }
        return sb.append("]").toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
