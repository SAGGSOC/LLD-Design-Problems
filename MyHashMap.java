import java.util.*;

/**
 * HashMap implementation — interview-ready, single file (~170 lines).
 *
 * Key features:
 *   - Separate chaining (singly-linked list per bucket)
 *   - Dynamic resize when load factor > 0.75
 *   - Handles null keys (stored at bucket 0)
 *   - Handles hash collisions correctly (keys with same hash land in same bucket)
 *   - O(1) average for get/put/remove; O(n) worst case
 *
 * Complexity:
 *   put:     O(1) amortized, O(n) worst case (all keys hash to same bucket)
 *   get:     O(1) avg, O(n) worst
 *   remove:  O(1) avg, O(n) worst
 *   resize:  O(n) — but happens rarely (every N inserts)
 *
 * What I'd mention but NOT implement in 30 minutes:
 *   - Tree-ifying buckets at size 8 (Java's optimization for worst-case collisions)
 *   - Concurrent access (use ConcurrentHashMap's segmented lock approach)
 */
public class MyHashMap<K, V> {

    private static final int DEFAULT_CAPACITY = 16;
    private static final double LOAD_FACTOR_THRESHOLD = 0.75;

    /** Singly-linked list node for separate chaining. */
    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> next;
        final int hash;  // cached to avoid recomputing on resize

        Node(K key, V value, int hash, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    private Node<K, V>[] buckets;
    private int size;

    @SuppressWarnings("unchecked")
    public MyHashMap() {
        this.buckets = (Node<K, V>[]) new Node[DEFAULT_CAPACITY];
        this.size = 0;
    }

    /**
     * Spread the hash to reduce collisions when bucket count is small.
     * Java's HashMap does this XOR with upper 16 bits.
     */
    private int hash(Object key) {
        if (key == null) return 0;
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    /** Bucket index from hash. length is always a power of 2 → use & instead of %. */
    private int indexFor(int hash, int length) {
        return hash & (length - 1);
    }

    public V put(K key, V value) {
        int hash = hash(key);
        int index = indexFor(hash, buckets.length);

        // Walk chain — update existing key if found
        for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
            if (node.hash == hash && Objects.equals(node.key, key)) {
                V old = node.value;
                node.value = value;
                return old;
            }
        }

        // Not found — insert at head of chain (O(1))
        buckets[index] = new Node<>(key, value, hash, buckets[index]);
        size++;

        if ((double) size / buckets.length > LOAD_FACTOR_THRESHOLD) {
            resize();
        }
        return null;
    }

    public V get(Object key) {
        int hash = hash(key);
        int index = indexFor(hash, buckets.length);
        for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
            if (node.hash == hash && Objects.equals(node.key, key)) {
                return node.value;
            }
        }
        return null;
    }

    public boolean containsKey(Object key) {
        int hash = hash(key);
        int index = indexFor(hash, buckets.length);
        for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
            if (node.hash == hash && Objects.equals(node.key, key)) {
                return true;
            }
        }
        return false;
    }

    public V remove(Object key) {
        int hash = hash(key);
        int index = indexFor(hash, buckets.length);

        Node<K, V> prev = null;
        for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
            if (node.hash == hash && Objects.equals(node.key, key)) {
                if (prev == null) {
                    buckets[index] = node.next;  // remove head
                } else {
                    prev.next = node.next;       // unlink middle/tail
                }
                size--;
                return node.value;
            }
            prev = node;
        }
        return null;
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    /**
     * Double capacity and re-index all entries.
     * Rehashing is necessary because indexFor() depends on length.
     *
     * Cached hash in Node avoids calling hashCode() again — critical for expensive keys.
     */
    @SuppressWarnings("unchecked")
    private void resize() {
        Node<K, V>[] oldBuckets = buckets;
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[oldBuckets.length * 2];

        for (Node<K, V> head : oldBuckets) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> next = node.next;
                int newIndex = indexFor(node.hash, newBuckets.length);
                node.next = newBuckets[newIndex];  // prepend to new chain
                newBuckets[newIndex] = node;
                node = next;
            }
        }
        this.buckets = newBuckets;
    }

    /** Pretty-print for debugging. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Node<K, V> head : buckets) {
            for (Node<K, V> node = head; node != null; node = node.next) {
                if (!first) sb.append(", ");
                sb.append(node.key).append("=").append(node.value);
                first = false;
            }
        }
        return sb.append("}").toString();
    }

    // ─── Demo ───

    public static void main(String[] args) {
        MyHashMap<String, Integer> map = new MyHashMap<>();

        // Basic operations
        map.put("one", 1);
        map.put("two", 2);
        map.put("three", 3);
        System.out.println("After puts: " + map);
        System.out.println("size=" + map.size() + ", get(two)=" + map.get("two"));

        // Update existing key
        Integer old = map.put("two", 22);
        System.out.println("\nUpdate two: old=" + old + ", new=" + map.get("two"));

        // Remove
        System.out.println("\nRemove one: " + map.remove("one"));
        System.out.println("containsKey(one)? " + map.containsKey("one"));
        System.out.println("Remove missing: " + map.remove("missing"));

        // Null keys and values
        map.put(null, 0);
        map.put("nullValue", null);
        System.out.println("\nget(null)=" + map.get(null));
        System.out.println("get(nullValue)=" + map.get("nullValue"));

        // Resize — insert enough to trigger
        System.out.println("\n--- Insert 100 keys to test resize ---");
        MyHashMap<Integer, String> big = new MyHashMap<>();
        for (int i = 0; i < 100; i++) big.put(i, "v" + i);
        System.out.println("size=" + big.size() + ", get(50)=" + big.get(50) + ", get(99)=" + big.get(99));

        // Hash collisions — keys that intentionally collide
        System.out.println("\n--- Collision test ---");
        MyHashMap<Key, String> colliding = new MyHashMap<>();
        colliding.put(new Key("A"), "a-value");
        colliding.put(new Key("B"), "b-value");  // same hashCode → same bucket
        colliding.put(new Key("C"), "c-value");  // same hashCode → same bucket
        System.out.println("Three keys with same hashCode, retrieve each:");
        System.out.println("  A → " + colliding.get(new Key("A")));
        System.out.println("  B → " + colliding.get(new Key("B")));
        System.out.println("  C → " + colliding.get(new Key("C")));
    }

    /** Test helper: all instances have same hashCode → forces bucket collisions. */
    static class Key {
        final String id;
        Key(String id) { this.id = id; }
        @Override public int hashCode() { return 42; }  // pathological
        @Override public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).id.equals(this.id);
        }
    }
}
