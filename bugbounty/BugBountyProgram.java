import java.util.*;

/**
 * Bug Bounty Program Management System — In-Memory (Interview Style)
 *
 * Features:
 *   - Preloaded users with roles (admin/agent)
 *   - Create, update, delete BugReports
 *   - Assign reports to users
 *   - Status workflow enforcement (state machine)
 *   - Comments (user-visible only)
 *   - List views: all, assignedToMe, completed/incomplete
 *   - Admin-only delete
 *
 * Design:
 *   - State machine for status transitions (Map of allowed transitions)
 *   - Role-based access control for delete
 *   - Only assigned user can change status
 */
public class BugBountyProgram {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class User {
        final String name;
        final String email;
        final String role; // "admin" or "agent"

        User(String name, String email, String role) {
            this.name = name;
            this.email = email;
            this.role = role;
        }
    }

    static class BugReport {
        String title;
        String description;
        String status;
        String severity;
        long bountyAmount;
        String reporterEmail;
        String assignedUser;
        long createdTimeStamp;
        long closedTimeStamp; // 0 if not closed
        final List<String> comments;

        BugReport(String title, String description, String severity, String reporterEmail) {
            this.title = title;
            this.description = description;
            this.status = "Open";
            this.severity = severity;
            this.bountyAmount = 0;
            this.reporterEmail = reporterEmail;
            this.assignedUser = "";
            this.createdTimeStamp = System.currentTimeMillis();
            this.closedTimeStamp = 0;
            this.comments = new ArrayList<>();
        }

        String toDisplayString() {
            return "title=" + title
                + ",status=" + status
                + ",severity=" + severity
                + ",bountyAmount=" + bountyAmount
                + ",reporterEmail=" + reporterEmail
                + ",assignedUser=" + assignedUser;
        }
    }

    // ═══════════════════════════════════════════════
    // Status Workflow (State Machine)
    // ═══════════════════════════════════════════════

    // Allowed transitions: fromStatus → Set of valid toStatuses
    private static final Map<String, Set<String>> VALID_TRANSITIONS = new HashMap<>();
    static {
        VALID_TRANSITIONS.put("Open", new HashSet<>(Arrays.asList("ReportReview")));
        VALID_TRANSITIONS.put("ReportReview", new HashSet<>(Arrays.asList("Rejected", "Acknowledged")));
        VALID_TRANSITIONS.put("Rejected", new HashSet<>(Arrays.asList("Closed")));
        VALID_TRANSITIONS.put("Acknowledged", new HashSet<>(Arrays.asList("BountyReview")));
        VALID_TRANSITIONS.put("BountyReview", new HashSet<>(Arrays.asList("BountyPaid")));
        VALID_TRANSITIONS.put("BountyPaid", new HashSet<>(Arrays.asList("Closed")));
        VALID_TRANSITIONS.put("Closed", new HashSet<>());
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, BugReport> reports = new LinkedHashMap<>();

    // ═══════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════

    public BugBountyProgram(List<String> preloadedUsers) {
        // Parse: "name=user1,email=user1@fk.com,role=admin"
        for (String userStr : preloadedUsers) {
            Map<String, String> fields = parseKeyValues(userStr);
            String name = fields.get("name");
            String email = fields.get("email");
            String role = fields.get("role");
            users.put(name, new User(name, email, role));
        }
    }

    // ═══════════════════════════════════════════════
    // 1. Create Bug Report
    // ═══════════════════════════════════════════════

    public String createBugReport(String title, String createdByUser, List<String> fields) {
        // Validate user
        if (!users.containsKey(createdByUser)) return "ERROR_INVALID_USER";

        // Validate unique title
        if (reports.containsKey(title)) return "ERROR_DUPLICATE_TITLE";

        // Parse fields
        Map<String, String> fieldMap = new HashMap<>();
        for (String f : fields) {
            String[] parts = f.split("=", 2);
            fieldMap.put(parts[0], parts[1]);
        }

        // Validate required fields
        if (!fieldMap.containsKey("description") || !fieldMap.containsKey("severity")
            || !fieldMap.containsKey("reporterEmail")) {
            return "ERROR_MISSING_REQUIRED_FIELDS";
        }

        if (fieldMap.get("reporterEmail").isEmpty()) return "ERROR_MISSING_REQUIRED_FIELDS";

        // Create
        BugReport report = new BugReport(
            title,
            fieldMap.get("description"),
            fieldMap.get("severity"),
            fieldMap.get("reporterEmail")
        );
        reports.put(title, report);
        return "OK";
    }

    // ═══════════════════════════════════════════════
    // 2. Update Bug Report
    // ═══════════════════════════════════════════════

    public String updateBugReport(String title, String updatedByUser, List<String> updates) {
        // Validate user
        if (!users.containsKey(updatedByUser)) return "ERROR_INVALID_USER";

        // Validate report exists
        BugReport report = reports.get(title);
        if (report == null) return "ERROR_NOT_FOUND";

        // Parse updates
        Map<String, String> updateMap = new LinkedHashMap<>();
        for (String u : updates) {
            String[] parts = u.split("=", 2);
            updateMap.put(parts[0], parts[1]);
        }

        // Validate assignedUser if present
        if (updateMap.containsKey("assignedUser")) {
            String assignee = updateMap.get("assignedUser");
            if (!users.containsKey(assignee)) return "ERROR_INVALID_USER_FOR_ASSIGNMENT";
        }

        // Validate status transition if present
        if (updateMap.containsKey("status")) {
            String newStatus = updateMap.get("status");

            // Only assigned user can change status
            if (!report.assignedUser.equals(updatedByUser)) return "ERROR_FORBIDDEN";

            // Validate transition
            Set<String> allowed = VALID_TRANSITIONS.getOrDefault(report.status, new HashSet<>());
            if (!allowed.contains(newStatus)) return "ERROR_INVALID_TRANSITION";
        }

        // Apply updates
        for (Map.Entry<String, String> entry : updateMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            switch (key) {
                case "description":
                    report.description = value;
                    break;
                case "severity":
                    report.severity = value;
                    break;
                case "status":
                    report.status = value;
                    if (value.equals("Closed")) {
                        report.closedTimeStamp = System.currentTimeMillis();
                    }
                    break;
                case "bountyAmount":
                    report.bountyAmount = Long.parseLong(value);
                    break;
                case "assignedUser":
                    report.assignedUser = value;
                    break;
                case "comment":
                    report.comments.add(updatedByUser + ": " + value);
                    break;
            }
        }

        return "OK";
    }

    // ═══════════════════════════════════════════════
    // 3. Delete Bug Report
    // ═══════════════════════════════════════════════

    public String deleteBugReport(String title, String deletedByUser) {
        // Validate user
        if (!users.containsKey(deletedByUser)) return "ERROR_INVALID_USER";

        // Validate report exists
        if (!reports.containsKey(title)) return "ERROR_NOT_FOUND";

        // Admin only
        User user = users.get(deletedByUser);
        if (!user.role.equals("admin")) return "ERROR_FORBIDDEN";

        reports.remove(title);
        return "OK";
    }

    // ═══════════════════════════════════════════════
    // 4. List Bug Reports
    // ═══════════════════════════════════════════════

    public List<String> listBugReports(String viewType, String requestedByUser) {
        if (!users.containsKey(requestedByUser)) return Collections.emptyList();

        List<String> result = new ArrayList<>();

        for (BugReport report : reports.values()) {
            boolean include = false;

            switch (viewType) {
                case "all":
                    include = true;
                    break;
                case "assignedToMe":
                    include = report.assignedUser.equals(requestedByUser);
                    break;
                case "assignedToMe:completed":
                    include = report.assignedUser.equals(requestedByUser)
                              && report.status.equals("Closed");
                    break;
                case "assignedToMe:incomplete":
                    include = report.assignedUser.equals(requestedByUser)
                              && !report.status.equals("Closed");
                    break;
            }

            if (include) {
                result.add(report.toDisplayString());
            }
        }

        return result;
    }

    // ═══════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════

    private Map<String, String> parseKeyValues(String input) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] pairs = input.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], kv[1]);
        }
        return map;
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        // ─── Example 1: Preload users, create BugReports ───
        System.out.println("═══ Example 1: Create ═══\n");

        BugBountyProgram program = new BugBountyProgram(Arrays.asList(
            "name=user1,email=user1@fk.com,role=admin",
            "name=user2,email=user2@fk.com,role=agent"
        ));

        System.out.println("Create Bug Title 1: " + program.createBugReport("Bug Title 1", "user1",
            Arrays.asList("description=Bug Description 1", "severity=P0", "reporterEmail=reporter.b1@email.com")));

        System.out.println("Create Bug Title 2: " + program.createBugReport("Bug Title 2", "user1",
            Arrays.asList("description=Bug Description 2", "severity=P0", "reporterEmail=reporter.b2@email.com")));

        // Duplicate
        System.out.println("Create duplicate: " + program.createBugReport("Bug Title 1", "user1",
            Arrays.asList("description=dup", "severity=P1", "reporterEmail=x@y.com")));

        // ─── Example 2: Assign, update status, bounty, comment ───
        System.out.println("\n═══ Example 2: Update Workflow ═══\n");

        System.out.println("Assign Bug 1 to user1: " + program.updateBugReport("Bug Title 1", "user1",
            Arrays.asList("assignedUser=user1")));

        System.out.println("Assign Bug 2 to user2: " + program.updateBugReport("Bug Title 2", "user1",
            Arrays.asList("assignedUser=user2")));

        System.out.println("Status → ReportReview: " + program.updateBugReport("Bug Title 1", "user1",
            Arrays.asList("status=ReportReview")));

        System.out.println("Status → Acknowledged: " + program.updateBugReport("Bug Title 1", "user1",
            Arrays.asList("status=Acknowledged")));

        System.out.println("Status → BountyReview + bounty + comment: " + program.updateBugReport("Bug Title 1", "user1",
            Arrays.asList("status=BountyReview", "bountyAmount=1000", "comment=comment text 1")));

        // Invalid transition
        System.out.println("Invalid transition Open→Closed: " + program.updateBugReport("Bug Title 2", "user2",
            Arrays.asList("status=Closed")));

        // Non-assigned user tries status change
        System.out.println("Non-assigned status change: " + program.updateBugReport("Bug Title 1", "user2",
            Arrays.asList("status=BountyPaid")));

        // ─── Example 3: Delete and list ───
        System.out.println("\n═══ Example 3: Delete & List ═══\n");

        // Non-admin delete
        System.out.println("Agent delete: " + program.deleteBugReport("Bug Title 2", "user2"));

        // Admin delete
        System.out.println("Admin delete: " + program.deleteBugReport("Bug Title 2", "user1"));

        System.out.println("\nList all:");
        program.listBugReports("all", "user1").forEach(s -> System.out.println("  " + s));

        System.out.println("\nList assignedToMe (user1):");
        program.listBugReports("assignedToMe", "user1").forEach(s -> System.out.println("  " + s));

        System.out.println("\nList assignedToMe:incomplete (user1):");
        program.listBugReports("assignedToMe:incomplete", "user1").forEach(s -> System.out.println("  " + s));

        System.out.println("\nList assignedToMe:completed (user1):");
        program.listBugReports("assignedToMe:completed", "user1").forEach(s -> System.out.println("  " + s));
    }
}
