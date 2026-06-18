import java.util.*;
import java.util.stream.Collectors;

/**
 * Gumble — Dating App (Interview Style)
 *
 * P0: Profile, interests, preferences, feed (getBestProfile), accept/decline, match
 * P1: Boost, lower priority for users with more matches, showStats
 * P2: Super-accept (highest priority, once per lifetime)
 *
 * Ranking (getBestProfile):
 *   1. Super-accept priority (P2)
 *   2. Preferred + already accepted requester (by mutual interests)
 *   3. Preferred + not accepted (by mutual interests)
 *   4. Unpreferred + already accepted requester (by mutual interests)
 *   Tie-breakers within bucket: boost > mutual interests > lower match count > lexicographic userId
 */
public class Gumble {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class UserProfile {
        String userId;
        String name;
        int age;
        String gender;
        Set<String> interests;
        int minAge, maxAge;
        String genderPreference;
        boolean boosted;
        boolean usedSuperAccept;
        String superAcceptTarget; // who this user super-accepted (null if none)
        int matchCount;

        // Track who this user has accepted/declined
        Set<String> accepted = new HashSet<>();
        Set<String> declined = new HashSet<>();
        Set<String> reactedTo = new HashSet<>(); // union of accepted + declined
        // Track matches
        Set<String> matches = new TreeSet<>();
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Set<String> allowedInterests;
    private final Map<String, UserProfile> users = new LinkedHashMap<>();
    // Track who has super-accepted whom: targetUserId → set of userIds who super-accepted them
    private final Map<String, Set<String>> superAcceptedBy = new HashMap<>();

    // ═══════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════

    public Gumble(List<String> allowedInterests) {
        this.allowedInterests = new HashSet<>();
        for (String interest : allowedInterests) {
            if (isValidInterest(interest)) {
                this.allowedInterests.add(interest);
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 1. Add or Update User
    // ═══════════════════════════════════════════════

    public boolean addOrUpdateUser(String userId, String name, int age, String gender,
                                    List<String> interests, int minAge, int maxAge, String genderPreference) {
        // Validations
        if (userId == null || userId.isEmpty()) return false;
        if (age < 18 || age > 100) return false;
        if (minAge < 18 || minAge > 100 || maxAge < 18 || maxAge > 100 || minAge > maxAge) return false;
        if (!isValidGender(gender)) return false;
        if (!isValidGenderPref(genderPreference)) return false;

        UserProfile profile = users.get(userId);
        if (profile == null) {
            profile = new UserProfile();
            profile.userId = userId;
            users.put(userId, profile);
        }

        // Overwrite profile fields
        profile.name = name;
        profile.age = age;
        profile.gender = gender;
        profile.minAge = minAge;
        profile.maxAge = maxAge;
        profile.genderPreference = genderPreference;

        // Filter interests: dedupe, valid format, in allowed set
        Set<String> validInterests = new LinkedHashSet<>();
        for (String interest : interests) {
            if (isValidInterest(interest) && allowedInterests.contains(interest)) {
                validInterests.add(interest);
            }
        }
        profile.interests = validInterests;

        return true;
    }

    // ═══════════════════════════════════════════════
    // 2. Get Best Profile
    // ═══════════════════════════════════════════════

    public String getBestProfile(String userId) {
        UserProfile requester = users.get(userId);
        if (requester == null) return "";

        // Check super-accepts toward this user first
        Set<String> superAccepters = superAcceptedBy.getOrDefault(userId, Collections.emptySet());
        List<String> activeSuperAccepts = new ArrayList<>();
        for (String sa : superAccepters) {
            if (!requester.reactedTo.contains(sa) && users.containsKey(sa)) {
                activeSuperAccepts.add(sa);
            }
        }
        if (!activeSuperAccepts.isEmpty()) {
            Collections.sort(activeSuperAccepts);
            return activeSuperAccepts.get(0);
        }

        // Build candidate list with buckets
        List<String[]> bucket2 = new ArrayList<>(); // preferred + accepted requester
        List<String[]> bucket3 = new ArrayList<>(); // preferred + not accepted
        List<String[]> bucket4 = new ArrayList<>(); // unpreferred + accepted requester

        for (UserProfile candidate : users.values()) {
            if (candidate.userId.equals(userId)) continue;
            if (requester.reactedTo.contains(candidate.userId)) continue;

            boolean preferred = isPreferred(requester, candidate);
            boolean candidateAcceptedRequester = candidate.accepted.contains(userId);

            if (preferred && candidateAcceptedRequester) {
                bucket2.add(new String[]{candidate.userId});
            } else if (preferred) {
                bucket3.add(new String[]{candidate.userId});
            } else if (candidateAcceptedRequester) {
                bucket4.add(new String[]{candidate.userId});
            }
            // unpreferred + not accepted → not eligible
        }

        // Try buckets in order
        String result = pickBest(requester, bucket2);
        if (!result.isEmpty()) return result;

        result = pickBest(requester, bucket3);
        if (!result.isEmpty()) return result;

        result = pickBest(requester, bucket4);
        if (!result.isEmpty()) return result;

        return "";
    }

    // ═══════════════════════════════════════════════
    // 3. Accept / Decline
    // ═══════════════════════════════════════════════

    public boolean acceptDeclineProfile(String userId, String targetUserId, boolean isAccepted) {
        UserProfile user = users.get(userId);
        UserProfile target = users.get(targetUserId);
        if (user == null || target == null) return false;
        if (userId.equals(targetUserId)) return false;
        if (user.reactedTo.contains(targetUserId)) return false; // already reacted

        user.reactedTo.add(targetUserId);
        if (isAccepted) {
            user.accepted.add(targetUserId);

            // Check for mutual match
            if (target.accepted.contains(userId)) {
                user.matches.add(targetUserId);
                target.matches.add(userId);
                user.matchCount++;
                target.matchCount++;
            }
        } else {
            user.declined.add(targetUserId);
        }

        // Remove from super-accept tracking (user reacted to super-accepter)
        Set<String> saSet = superAcceptedBy.get(userId);
        if (saSet != null) {
            saSet.remove(targetUserId);
        }

        return true;
    }

    // ═══════════════════════════════════════════════
    // 4. List Matched Profiles
    // ═══════════════════════════════════════════════

    public List<String> listMatchedProfiles(String userId) {
        UserProfile user = users.get(userId);
        if (user == null) return Collections.emptyList();
        return new ArrayList<>(user.matches); // TreeSet already sorted
    }

    // ═══════════════════════════════════════════════
    // 5. Buy Boost (P1)
    // ═══════════════════════════════════════════════

    public boolean buyBoost(String userId) {
        UserProfile user = users.get(userId);
        if (user == null) return false;
        user.boosted = true;
        return true;
    }

    // ═══════════════════════════════════════════════
    // 6. Show Stats (P1)
    // ═══════════════════════════════════════════════

    public List<String> showStats(int topN) {
        List<String> stats = new ArrayList<>();

        // Total users
        stats.add("totalUsers=" + users.size());

        // Matched users (at least 1 match)
        long matchedCount = users.values().stream().filter(u -> u.matchCount > 0).count();
        stats.add("matchedUsers=" + matchedCount);

        // Top-N by match count
        List<UserProfile> sorted = new ArrayList<>(users.values());
        sorted.sort((a, b) -> {
            if (b.matchCount != a.matchCount) return Integer.compare(b.matchCount, a.matchCount);
            return a.userId.compareTo(b.userId);
        });
        StringBuilder topN_sb = new StringBuilder();
        for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
            if (i > 0) topN_sb.append(",");
            topN_sb.append(sorted.get(i).userId).append("-").append(sorted.get(i).matchCount);
        }
        stats.add("topNByMatches=" + topN_sb.toString());

        // Gender cohort
        int male = 0, female = 0, other = 0;
        for (UserProfile u : users.values()) {
            switch (u.gender) {
                case "MALE": male++; break;
                case "FEMALE": female++; break;
                case "OTHER": other++; break;
            }
        }
        stats.add("genderCohort=MALE:" + male + ",FEMALE:" + female + ",OTHER:" + other);

        // Age cohort
        int a18_25 = 0, a26_35 = 0, a36_50 = 0, a51_100 = 0;
        for (UserProfile u : users.values()) {
            if (u.age >= 18 && u.age <= 25) a18_25++;
            else if (u.age >= 26 && u.age <= 35) a26_35++;
            else if (u.age >= 36 && u.age <= 50) a36_50++;
            else if (u.age >= 51 && u.age <= 100) a51_100++;
        }
        stats.add("ageCohort=18-25:" + a18_25 + ",26-35:" + a26_35 + ",36-50:" + a36_50 + ",51-100:" + a51_100);

        return stats;
    }

    // ═══════════════════════════════════════════════
    // 7. Super Accept (P2)
    // ═══════════════════════════════════════════════

    public boolean superAcceptProfile(String userId, String targetUserId) {
        UserProfile user = users.get(userId);
        UserProfile target = users.get(targetUserId);
        if (user == null || target == null) return false;
        if (userId.equals(targetUserId)) return false;
        if (user.usedSuperAccept) return false; // once per lifetime

        user.usedSuperAccept = true;
        user.superAcceptTarget = targetUserId;

        // Track in target's super-accept list
        superAcceptedBy.computeIfAbsent(targetUserId, k -> new HashSet<>()).add(userId);
        return true;
    }

    // ═══════════════════════════════════════════════
    // Internal Helpers
    // ═══════════════════════════════════════════════

    private boolean isPreferred(UserProfile requester, UserProfile candidate) {
        // Age in range
        if (candidate.age < requester.minAge || candidate.age > requester.maxAge) return false;
        // Gender match
        if (!requester.genderPreference.equals("ANY") && !requester.genderPreference.equals(candidate.gender)) return false;
        return true;
    }

    private int mutualInterests(UserProfile a, UserProfile b) {
        int count = 0;
        for (String interest : a.interests) {
            if (b.interests.contains(interest)) count++;
        }
        return count;
    }

    /** Pick best candidate from a bucket using tie-breakers. */
    private String pickBest(UserProfile requester, List<String[]> bucket) {
        if (bucket.isEmpty()) return "";

        String bestId = null;
        int bestMutual = -1;
        boolean bestBoosted = false;
        int bestMatchCount = Integer.MAX_VALUE;

        for (String[] entry : bucket) {
            String candidateId = entry[0];
            UserProfile candidate = users.get(candidateId);
            int mutual = mutualInterests(requester, candidate);
            boolean boosted = candidate.boosted;
            int matchCount = candidate.matchCount;

            if (bestId == null || compareCandidates(boosted, mutual, matchCount, candidateId,
                                                    bestBoosted, bestMutual, bestMatchCount, bestId) < 0) {
                bestId = candidateId;
                bestMutual = mutual;
                bestBoosted = boosted;
                bestMatchCount = matchCount;
            }
        }

        return bestId != null ? bestId : "";
    }

    /**
     * Compare two candidates. Returns < 0 if candidate A should rank higher.
     * Order: boost > higher mutual interests > lower match count > lexicographic smaller userId
     */
    private int compareCandidates(boolean boostedA, int mutualA, int matchCountA, String idA,
                                   boolean boostedB, int mutualB, int matchCountB, String idB) {
        // Boost first
        if (boostedA && !boostedB) return -1;
        if (!boostedA && boostedB) return 1;
        // Higher mutual interests
        if (mutualA != mutualB) return Integer.compare(mutualB, mutualA); // descending
        // Lower match count (P1)
        if (matchCountA != matchCountB) return Integer.compare(matchCountA, matchCountB); // ascending
        // Lexicographic
        return idA.compareTo(idB);
    }

    private boolean isValidInterest(String interest) {
        if (interest == null || interest.isEmpty()) return false;
        return interest.matches("[a-z-]+");
    }

    private boolean isValidGender(String gender) {
        return "MALE".equals(gender) || "FEMALE".equals(gender) || "OTHER".equals(gender);
    }

    private boolean isValidGenderPref(String pref) {
        return "MALE".equals(pref) || "FEMALE".equals(pref) || "OTHER".equals(pref) || "ANY".equals(pref);
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        Gumble app = new Gumble(Arrays.asList(
            "movies", "books", "travel", "music", "pets", "football", "standup-comedy", "dog-lover"
        ));

        // ─── Example 1 ───
        System.out.println("═══ Example 1: Profiles + Ranking ═══\n");

        app.addOrUpdateUser("u1", "Asha", 24, "FEMALE", Arrays.asList("movies", "books", "travel"), 22, 26, "MALE");
        app.addOrUpdateUser("u2", "Ravi", 25, "MALE", Arrays.asList("movies", "football", "music"), 18, 100, "ANY");
        app.addOrUpdateUser("u3", "Neel", 23, "MALE", Arrays.asList("movies", "books", "pets"), 18, 100, "ANY");
        app.addOrUpdateUser("u4", "Maya", 24, "FEMALE", Arrays.asList("travel", "music"), 18, 100, "ANY");

        System.out.println("getBestProfile(u1): " + app.getBestProfile("u1")); // u3

        // Overwrite u1
        app.addOrUpdateUser("u1", "Asha Sharma", 25, "FEMALE",
            Arrays.asList("music", "travel", "music", "INVALID", "dog-lover", "unknown"), 23, 30, "ANY");
        System.out.println("getBestProfile(u1) after overwrite: " + app.getBestProfile("u1")); // u4

        // ─── Example 2: Accept/Decline/Match ───
        System.out.println("\n═══ Example 2: Accept/Decline/Match ═══\n");

        app.addOrUpdateUser("u5", "Dev", 29, "MALE", Arrays.asList("travel", "books"), 18, 100, "ANY");
        app.addOrUpdateUser("u1", "Asha Sharma", 25, "FEMALE", Arrays.asList("music", "travel"), 22, 26, "MALE");

        System.out.println("u1 accepts u2: " + app.acceptDeclineProfile("u1", "u2", true));   // true
        System.out.println("u1 accepts u2 again: " + app.acceptDeclineProfile("u1", "u2", false)); // false
        System.out.println("getBestProfile(u1): " + app.getBestProfile("u1"));                // u3
        System.out.println("u1 declines u3: " + app.acceptDeclineProfile("u1", "u3", false)); // true
        System.out.println("getBestProfile(u1): " + app.getBestProfile("u1"));                // "" (no eligible)
        System.out.println("u5 accepts u1: " + app.acceptDeclineProfile("u5", "u1", true));   // true
        System.out.println("getBestProfile(u1): " + app.getBestProfile("u1"));                // u5
        System.out.println("u1 accepts u5: " + app.acceptDeclineProfile("u1", "u5", true));   // true (MATCH!)
        System.out.println("listMatchedProfiles(u1): " + app.listMatchedProfiles("u1"));     // [u5]
        System.out.println("listMatchedProfiles(u5): " + app.listMatchedProfiles("u5"));     // [u1]

        // ─── Example 3: Boost + Super-Accept + Stats ───
        System.out.println("\n═══ Example 3: P1 + P2 ═══\n");

        app.addOrUpdateUser("u6", "Kiran", 24, "MALE", Arrays.asList("travel"), 18, 100, "ANY");
        app.addOrUpdateUser("u7", "Raj", 24, "MALE", Arrays.asList("travel"), 18, 100, "ANY");
        app.addOrUpdateUser("u8", "Ira", 24, "FEMALE", Arrays.asList("travel"), 18, 100, "ANY");
        app.addOrUpdateUser("u9", "Sara", 24, "FEMALE", Arrays.asList("travel"), 18, 100, "ANY");

        app.acceptDeclineProfile("u6", "u8", true);
        app.acceptDeclineProfile("u8", "u6", true); // match u6-u8
        app.acceptDeclineProfile("u6", "u9", true);
        app.acceptDeclineProfile("u9", "u6", true); // match u6-u9

        app.addOrUpdateUser("u4", "Maya", 24, "FEMALE", Arrays.asList("travel", "music"), 24, 24, "MALE");

        System.out.println("getBestProfile(u4): " + app.getBestProfile("u4")); // u7 (lower match count than u6)

        app.buyBoost("u6");
        System.out.println("getBestProfile(u4) after boost: " + app.getBestProfile("u4")); // u6 (boosted)

        app.superAcceptProfile("u2", "u4");
        System.out.println("getBestProfile(u4) after super-accept: " + app.getBestProfile("u4")); // u2

        System.out.println("superAccept u2 again: " + app.superAcceptProfile("u2", "u1")); // false (already used)

        System.out.println("\nshowStats(3):");
        app.showStats(3).forEach(s -> System.out.println("  " + s));
    }
}
