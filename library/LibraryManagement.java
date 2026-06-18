import java.util.*;

/**
 * Library Management System — In-Memory (Interview Style)
 *
 * Features:
 *   - Add books (auto-generated IDs based on author prefix)
 *   - Register/unregister users
 *   - Borrow with FIFO waitlist + HELD_FOR mechanism
 *   - Return with late fine calculation (20/day after 14 days)
 *   - Auditing: usersHavingBook, booksIssuedToUser
 *   - Bonus: one copy per user per book
 *
 * Book ID format: <PREFIX><NUMBER>
 *   PREFIX = first 3 chars of author's last name (uppercased)
 *   NUMBER = 4-digit sequence per prefix, starting at 1000
 */
public class LibraryManagement {

    // ═══════════════════════════════════════════════
    // Models
    // ═══════════════════════════════════════════════

    static class Book {
        final String bookId;
        final String title;
        final String author;
        int totalCopies;
        // Tracking: who has issued copies, and borrow day
        final Map<String, Integer> issuedTo; // userId → borrowDay
        // FIFO waitlist
        final LinkedList<String> waitlist;
        // Held copies: userId → true (reserved for that user)
        final Set<String> heldFor;

        Book(String bookId, String title, String author, int copies) {
            this.bookId = bookId;
            this.title = title;
            this.author = author;
            this.totalCopies = copies;
            this.issuedTo = new LinkedHashMap<>();
            this.waitlist = new LinkedList<>();
            this.heldFor = new HashSet<>();
        }

        int availableCopies() {
            return totalCopies - issuedTo.size() - heldFor.size();
        }
    }

    static class User {
        final String userId;
        final String name;
        final Set<String> issuedBooks; // bookIds currently issued

        User(String userId, String name) {
            this.userId = userId;
            this.name = name;
            this.issuedBooks = new TreeSet<>();
        }
    }

    // ═══════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════

    private final Map<String, Book> books = new LinkedHashMap<>();          // bookId → Book
    private final Map<String, String> titleAuthorToId = new HashMap<>();    // "title|author" → bookId
    private final Map<String, User> users = new LinkedHashMap<>();          // userId → User
    private final Map<String, Integer> prefixCounters = new HashMap<>();    // prefix → next number

    // ═══════════════════════════════════════════════
    // 1. Add Book
    // ═══════════════════════════════════════════════

    public String addBook(String title, String author, int copies) {
        if (title == null || title.isEmpty() || author == null || author.isEmpty()) return "INVALID_INPUT";
        if (copies <= 0) return "INVALID_COPIES";

        String key = title + "|" + author;

        // If same (title, author) exists, increase copies
        if (titleAuthorToId.containsKey(key)) {
            String existingId = titleAuthorToId.get(key);
            books.get(existingId).totalCopies += copies;
            return "BOOK_ID," + existingId;
        }

        // Generate new bookId
        String prefix = generatePrefix(author);
        int number = prefixCounters.getOrDefault(prefix, 1000);
        String bookId = prefix + number;
        prefixCounters.put(prefix, number + 1);

        Book book = new Book(bookId, title, author, copies);
        books.put(bookId, book);
        titleAuthorToId.put(key, bookId);

        return "BOOK_ID," + bookId;
    }

    // ═══════════════════════════════════════════════
    // 2. Register User
    // ═══════════════════════════════════════════════

    public String registerUser(String userId, String name) {
        if (userId == null || userId.isEmpty() || name == null || name.isEmpty()) return "INVALID_INPUT";
        if (users.containsKey(userId)) return "USER_ALREADY_EXISTS";

        users.put(userId, new User(userId, name));
        return "SUCCESS";
    }

    // ═══════════════════════════════════════════════
    // 3. Unregister User
    // ═══════════════════════════════════════════════

    public String unregisterUser(String userId) {
        User user = users.get(userId);
        if (user == null) return "USER_NOT_FOUND";
        if (!user.issuedBooks.isEmpty()) return "USER_HAS_ISSUED_BOOKS";

        // Check if user is in any waitlist
        for (Book book : books.values()) {
            if (book.waitlist.contains(userId)) return "USER_IN_WAITLIST";
        }

        users.remove(userId);
        return "SUCCESS";
    }

    // ═══════════════════════════════════════════════
    // 4. Request Borrow
    // ═══════════════════════════════════════════════

    public String requestBorrow(String userId, String bookId, int requestDay) {
        if (requestDay < 0) return "INVALID_DAY";

        User user = users.get(userId);
        if (user == null) return "USER_NOT_FOUND";

        Book book = books.get(bookId);
        if (book == null) return "BOOK_NOT_FOUND";

        // Bonus: already issued to this user
        if (book.issuedTo.containsKey(userId)) return "ALREADY_ISSUED_TO_USER";

        // Already in waitlist
        if (book.waitlist.contains(userId)) return "ALREADY_WAITLISTED";

        // Check if a copy is HELD_FOR this user
        if (book.heldFor.contains(userId)) {
            book.heldFor.remove(userId);
            book.issuedTo.put(userId, requestDay);
            user.issuedBooks.add(bookId);
            return "ISSUED";
        }

        // Check if a copy is available (not issued, not held)
        if (book.availableCopies() > 0) {
            book.issuedTo.put(userId, requestDay);
            user.issuedBooks.add(bookId);
            return "ISSUED";
        }

        // No copy available → waitlist
        book.waitlist.add(userId);
        int position = book.waitlist.size();
        return "WAITLISTED," + position;
    }

    // ═══════════════════════════════════════════════
    // 5. Return Book
    // ═══════════════════════════════════════════════

    public String returnBook(String userId, String bookId, int returnDay) {
        if (returnDay < 0) return "INVALID_DAY";

        User user = users.get(userId);
        if (user == null) return "USER_NOT_FOUND";

        Book book = books.get(bookId);
        if (book == null) return "BOOK_NOT_FOUND";

        if (!book.issuedTo.containsKey(userId)) return "NOT_ISSUED_TO_USER";

        int borrowDay = book.issuedTo.get(userId);
        if (returnDay < borrowDay) return "INVALID_DAY";

        // Return the book
        book.issuedTo.remove(userId);
        user.issuedBooks.remove(bookId);

        // Calculate fine
        int borrowDuration = returnDay - borrowDay;
        int fine = 0;
        if (borrowDuration > 14) {
            int delayDays = borrowDuration - 14;
            fine = delayDays * 20;
        }

        // Process waitlist: dequeue FIFO head, hold copy for them
        if (!book.waitlist.isEmpty()) {
            String nextUser = book.waitlist.pollFirst();
            book.heldFor.add(nextUser);
        }

        return "RETURNED," + fine;
    }

    // ═══════════════════════════════════════════════
    // 6. Users Having Book (Audit)
    // ═══════════════════════════════════════════════

    public List<String> usersHavingBook(String bookId) {
        Book book = books.get(bookId);
        if (book == null) return Collections.emptyList();

        List<String> result = new ArrayList<>(book.issuedTo.keySet());
        Collections.sort(result);
        return result;
    }

    // ═══════════════════════════════════════════════
    // 7. Books Issued to User (Audit)
    // ═══════════════════════════════════════════════

    public List<String> booksIssuedToUser(String userId) {
        User user = users.get(userId);
        if (user == null) return Collections.emptyList();
        return new ArrayList<>(user.issuedBooks); // TreeSet already sorted
    }

    // ═══════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════

    private String generatePrefix(String author) {
        // Last token is last name
        String[] tokens = author.trim().split("\\s+");
        String lastName = tokens[tokens.length - 1];
        String prefix = lastName.substring(0, Math.min(3, lastName.length())).toUpperCase();
        return prefix;
    }

    // ═══════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════

    public static void main(String[] args) {
        LibraryManagement lib = new LibraryManagement();

        // ─── Example 1: Add books, register, issue ───
        System.out.println("═══ Example 1 ═══\n");

        System.out.println(lib.addBook("Harry Potter and the Sorcerer's Stone", "J K Rowling", 2));  // BOOK_ID,ROW1000
        System.out.println(lib.addBook("Harry Potter and the Sorcerer's Stone", "J K Rowling", 1));  // BOOK_ID,ROW1000 (copies increase)
        System.out.println(lib.registerUser("U1", "Alice"));  // SUCCESS
        System.out.println(lib.registerUser("U2", "Bob"));    // SUCCESS
        System.out.println(lib.requestBorrow("U1", "ROW1000", 1));  // ISSUED
        System.out.println(lib.requestBorrow("U2", "ROW1000", 1));  // ISSUED
        System.out.println("usersHavingBook: " + lib.usersHavingBook("ROW1000"));  // [U1, U2]
        System.out.println("booksIssuedToUser U1: " + lib.booksIssuedToUser("U1")); // [ROW1000]

        // ─── Example 2: Waitlist + HELD_FOR + fine ───
        System.out.println("\n═══ Example 2 ═══\n");

        LibraryManagement lib2 = new LibraryManagement();
        lib2.registerUser("U1", "Alice");
        lib2.registerUser("U2", "Bob");
        lib2.registerUser("U3", "Charlie");
        System.out.println(lib2.addBook("Clean Code", "Robert C Martin", 1));  // BOOK_ID,MAR1000

        System.out.println(lib2.requestBorrow("U1", "MAR1000", 5));   // ISSUED
        System.out.println(lib2.requestBorrow("U2", "MAR1000", 6));   // WAITLISTED,1
        System.out.println(lib2.requestBorrow("U3", "MAR1000", 6));   // WAITLISTED,2
        System.out.println(lib2.returnBook("U1", "MAR1000", 10));     // RETURNED,0 (HELD_FOR U2)
        System.out.println(lib2.requestBorrow("U3", "MAR1000", 10));  // ALREADY_WAITLISTED
        System.out.println(lib2.requestBorrow("U2", "MAR1000", 10));  // ISSUED (HELD_FOR U2)
        System.out.println(lib2.returnBook("U2", "MAR1000", 30));     // RETURNED,120 (delay=6 days)
        System.out.println(lib2.requestBorrow("U3", "MAR1000", 30));  // ISSUED (HELD_FOR U3)
        System.out.println("usersHavingBook: " + lib2.usersHavingBook("MAR1000"));  // [U3]
        System.out.println("booksIssuedToUser U2: " + lib2.booksIssuedToUser("U2")); // []

        // ─── Example 3: Fine calculation ───
        System.out.println("\n═══ Example 3 ═══\n");

        LibraryManagement lib3 = new LibraryManagement();
        lib3.registerUser("U1", "Alice");
        System.out.println(lib3.addBook("The Hobbit", "J R R Tolkien", 1));  // BOOK_ID,TOL1000

        System.out.println(lib3.requestBorrow("U1", "TOL1000", 1));   // ISSUED
        System.out.println(lib3.returnBook("U1", "TOL1000", 20));     // RETURNED,100
        System.out.println("usersHavingBook: " + lib3.usersHavingBook("TOL1000"));  // []
        System.out.println("booksIssuedToUser U1: " + lib3.booksIssuedToUser("U1")); // []
    }
}
