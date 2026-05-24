import java.util.*;

/**
 * Transactional Cache — interview-ready single file.
 *
 * Supports: put / get / delete, plus BEGIN / COMMIT / ROLLBACK.
 * Transactions can be NESTED — a stack of write layers.
 *
 * Key design:
 *   - Global store: the committed source of truth.
 *   - Transaction stack: each BEGIN pushes a new "write layer".
 *     A write layer records what THIS transaction did — including TOMBSTONES
 *     for deletions so we can distinguish "deleted in tx" from "not touched by tx".
 *
 *   - get(k): walk the stack top-down. First layer that mentions k wins:
 *       TOMBSTONE → return null
 *       value     → return value
 *     If no layer mentions k, fall through to global.
 *
 *   - COMMIT: merge the top layer into the layer below (or global if top is outermost).
 *   - ROLLBACK: discard the top layer.
 *
 * The 5 corner cases in delete that trip candidates up:
 *   1. Delete a key that exists only in global → must write TOMBSTONE (not just a regular entry)
 *   2. Delete a key just put()'d in the same tx → remove from this layer (+ still tombstone if
 *      it existed in a parent/global, so parents don't resurface it)
 *   3. Delete a key not touched by tx and not in global → no-op (or throw, per spec)
 *   4. Double-delete in same tx → idempotent, no-op
 *   5. Delete a key tombstoned in a parent tx but put() by current tx → tombstone again
 */
public class TransactionalCache {

    /**
     * Sentinel value representing a deletion at a given transaction layer.
     * Stored as a value in the layer map — because null is a valid "not present"
     * marker, we need something distinguishable.
     */
    private static final String TOMBSTONE = "\0__TOMBSTONE__\0";  // unique token

    /** Committed data — the source of truth outside transactions. */
    private final Map<String, String> globalStore = new HashMap<>();

    /** Stack of active transaction layers. Top = innermost / most recent BEGIN. */
    private final Deque<Map<String, String>> txStack = new ArrayDeque<>();

    // ─── Public API ───

    public void put(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (txStack.isEmpty()) {
            globalStore.put(key, value);
        } else {
            txStack.peek().put(key, value);
        }
    }

    public String get(String key) {
        Objects.requireNonNull(key);
        // Walk stack from top (innermost) to bottom (outermost)
        for (Map<String, String> layer : txStack) {
            if (layer.containsKey(key)) {
                String v = layer.get(key);
                return v == TOMBSTONE ? null : v;
            }
        }
        // Not touched by any active transaction → return committed value
        return globalStore.get(key);
    }

    public void delete(String key) {
        Objects.requireNonNull(key);
        if (txStack.isEmpty()) {
            // Outside transaction: just remove from global
            globalStore.remove(key);
            return;
        }

        // Inside transaction: decide based on current state
        // Case 1: key not present anywhere → no-op (we can be strict and throw, but
        //         most real caches are idempotent here)
        if (!exists(key)) return;

        // Cases 2-5: always write TOMBSTONE in the current layer. This shadows any
        // value from parent layers or global. On ROLLBACK we throw the whole layer
        // away; on COMMIT the tombstone propagates down.
        txStack.peek().put(key, TOMBSTONE);
    }

    public boolean exists(String key) {
        Objects.requireNonNull(key);
        for (Map<String, String> layer : txStack) {
            if (layer.containsKey(key)) {
                return layer.get(key) != TOMBSTONE;
            }
        }
        return globalStore.containsKey(key);
    }

    public void begin() {
        txStack.push(new HashMap<>());
    }

    public void commit() {
        if (txStack.isEmpty()) throw new IllegalStateException("No active transaction");
        Map<String, String> current = txStack.pop();
        // Merge into the layer below (parent tx) if one exists, else into global
        Map<String, String> target = txStack.isEmpty() ? globalStore : txStack.peek();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (entry.getValue() == TOMBSTONE) {
                // Tombstone at outermost commit → actually delete from global
                if (txStack.isEmpty()) {
                    globalStore.remove(entry.getKey());
                } else {
                    // Nested: propagate the tombstone to parent so parent also sees the deletion
                    target.put(entry.getKey(), TOMBSTONE);
                }
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public void rollback() {
        if (txStack.isEmpty()) throw new IllegalStateException("No active transaction");
        txStack.pop();  // throw away the layer's changes
    }

    // ─── Demo — walks through every tricky case ───

    public static void main(String[] args) {
        TransactionalCache cache = new TransactionalCache();

        System.out.println("=== Basic put/get/delete (no transaction) ===");
        cache.put("a", "1");
        cache.put("b", "2");
        System.out.println("get(a) = " + cache.get("a"));
        cache.delete("a");
        System.out.println("after delete, get(a) = " + cache.get("a"));
        System.out.println("get(b) = " + cache.get("b"));

        System.out.println("\n=== Corner case 1: delete a global-only key inside tx, then ROLLBACK ===");
        cache.put("x", "global-x");
        cache.begin();
        System.out.println("  get(x) in tx = " + cache.get("x"));
        cache.delete("x");
        System.out.println("  after delete(x) in tx, get(x) = " + cache.get("x") + "  (should be null)");
        cache.rollback();
        System.out.println("  after ROLLBACK, get(x) = " + cache.get("x") + "  (should be 'global-x')");

        System.out.println("\n=== Corner case 2: delete a global-only key inside tx, then COMMIT ===");
        cache.begin();
        cache.delete("x");
        cache.commit();
        System.out.println("  after COMMIT, get(x) = " + cache.get("x") + "  (should be null)");

        System.out.println("\n=== Corner case 3: put in tx then delete in same tx ===");
        cache.begin();
        cache.put("y", "tx-y");
        System.out.println("  after put in tx, get(y) = " + cache.get("y"));
        cache.delete("y");
        System.out.println("  after delete in tx, get(y) = " + cache.get("y") + "  (should be null)");
        cache.commit();
        System.out.println("  after COMMIT, get(y) = " + cache.get("y") + "  (should be null)");

        System.out.println("\n=== Corner case 4: double-delete is idempotent ===");
        cache.put("z", "global-z");
        cache.begin();
        cache.delete("z");
        cache.delete("z");  // no-op or idempotent — must not error
        System.out.println("  get(z) after double-delete = " + cache.get("z") + "  (should be null)");
        cache.rollback();
        System.out.println("  after ROLLBACK, get(z) = " + cache.get("z") + "  (should be 'global-z')");

        System.out.println("\n=== Corner case 5: nested transactions ===");
        cache.put("k", "v0");
        cache.begin();                  // outer
        cache.put("k", "v1");
        System.out.println("  outer tx: get(k) = " + cache.get("k"));
        cache.begin();                  // inner
        cache.delete("k");
        System.out.println("  inner tx: get(k) = " + cache.get("k"));
        cache.rollback();               // roll back inner only
        System.out.println("  after inner ROLLBACK: get(k) = " + cache.get("k") + "  (should be 'v1')");
        cache.commit();                 // commit outer
        System.out.println("  after outer COMMIT: get(k) = " + cache.get("k") + "  (should be 'v1')");

        System.out.println("\n=== Corner case 6: delete in inner tx, commit, rollback outer ===");
        // k = 'v1' from above
        cache.begin();                  // outer
        cache.begin();                  // inner
        cache.delete("k");
        cache.commit();                 // inner commit → k tombstoned in OUTER layer
        System.out.println("  outer tx after inner commit: get(k) = " + cache.get("k") + "  (should be null)");
        cache.rollback();               // outer rollback → tombstone discarded
        System.out.println("  after outer ROLLBACK: get(k) = " + cache.get("k") + "  (should be 'v1')");

        System.out.println("\n=== Corner case 7: delete of non-existent key is no-op ===");
        cache.begin();
        cache.delete("neverExisted");  // must not error
        cache.commit();
        System.out.println("  get(neverExisted) = " + cache.get("neverExisted") + "  (should be null)");

        System.out.println("\n=== Corner case 8: commit/rollback with no active tx throws ===");
        try { cache.commit(); System.out.println("  [FAIL]"); }
        catch (IllegalStateException e) { System.out.println("  [OK]   commit threw: " + e.getMessage()); }
        try { cache.rollback(); System.out.println("  [FAIL]"); }
        catch (IllegalStateException e) { System.out.println("  [OK]   rollback threw: " + e.getMessage()); }
    }
}
