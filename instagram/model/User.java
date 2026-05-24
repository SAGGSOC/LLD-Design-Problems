package instagram.model;

import java.time.Instant;
import java.util.Objects;

public class User {
    private final String userId;
    private final String username;
    private String bio;
    private String avatarUrl;
    private final Instant joinedAt;

    public User(String userId, String username, String bio, String avatarUrl) {
        this.userId = userId;
        this.username = username;
        this.bio = bio;
        this.avatarUrl = avatarUrl;
        this.joinedAt = Instant.now();
    }

    public void updateBio(String bio)             { this.bio = bio; }
    public void updateAvatar(String avatarUrl)    { this.avatarUrl = avatarUrl; }

    public String getUserId()       { return userId; }
    public String getUsername()     { return username; }
    public String getBio()          { return bio; }
    public String getAvatarUrl()    { return avatarUrl; }
    public Instant getJoinedAt()    { return joinedAt; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof User)) return false;
        return Objects.equals(userId, ((User) o).userId);
    }

    @Override
    public int hashCode() { return Objects.hash(userId); }

    @Override
    public String toString() { return "@" + username; }
}
