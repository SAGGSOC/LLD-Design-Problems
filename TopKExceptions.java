import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Top-K exceptions in a sliding 24h window.
 *
 * Core data structures:
 *   - Per fingerprint: 1440 minute-buckets (one per minute of day) + running total.
 *     Bucketization keeps the sliding window tractable without storing each event.
 *
 *   - Global sorted structure for top-K queries:
 *     A TreeSet ordered by (count DESC, fingerprint) for O(log N) insertion
 *     and O(K) top-K retrieval.
 *
 * Operations:
 *   recordException(fingerprint, timestamp): O(log N)
 *   getTopK(K):                               O(K)
 *   tick():                                   O(F) — F = fingerprints with count in rolled-off minute
 *
 * This is the single-process version. For distributed, each data structure
 * becomes a Redis key. The algorithm is unchanged.
 */
public class TopKExceptions {

    static final int BUCKET_COUNT = 1440;  // 1 per minute of day

    /** Sliding-window counter for one fingerprint. */
    static class SlidingCounter {
        final String fingerprint;
        final long[] buckets = new long[BUCKET_COUNT];
        long total = 0;
        long lastUpdatedBucket = -1;

        SlidingCounter(String fingerprint) { this.fingerprint = fingerprint; }

        /** Increment count for the given bucket. */
        void increment(int bucket) {
            buckets[bucket]++;
            total++;
        }

        /** Roll off the oldest bucket (called when window advances). */
        void rollOff(int bucket) {
            total -= buckets[bucket];
            buckets[bucket] = 0;
        }
    }

    /** Main service. */
    static class TopKService {
        // fingerprint → counter
        private final Map<String, SlidingCounter> counters = new ConcurrentHashMap<>();

        // Sorted by (count desc, fingerprint). Gives O(K) top-K in natural order.
        // TreeSet is ordered, so "first K" is top-K.
        private final TreeSet<SlidingCounter> ranking = new TreeSet<>(
            Comparator.<SlidingCounter>comparingLong(c -> -c.total)
                .thenComparing(c -> c.fingerprint));

        private final Object lock = new Object();  // guards ranking + counter state

        /**
         * Record an exception occurrence. timestamp is unix epoch seconds.
         * We bucket by minute-of-day (0..1439).
         */
        public void recordException(String fingerprint, long timestampSec) {
            int bucket = (int) ((timestampSec / 60) % BUCKET_COUNT);

            synchronized (lock) {
                SlidingCounter counter = counters.computeIfAbsent(fingerprint, SlidingCounter::new);

                // Remove from ranking before mutating (TreeSet requires consistent ordering)
                ranking.remove(counter);
                counter.increment(bucket);
                ranking.add(counter);
            }
        }

        /**
         * Advance the sliding window. Should be called at the start of every new minute
         * to "roll off" the bucket from 24h ago.
         *
         * In production: scheduled job. For the demo, we call it manually.
         */
        public void tick(long nowSec) {
            // The bucket to roll off is the one 1440 minutes ago (== current bucket, since we
            // reuse slots cyclically). We need to reset it BEFORE new events land there.
            // Called at minute boundary.
            int bucketToRollOff = (int) ((nowSec / 60) % BUCKET_COUNT);

            synchronized (lock) {
                for (SlidingCounter counter : new ArrayList<>(counters.values())) {
                    if (counter.buckets[bucketToRollOff] > 0) {
                        ranking.remove(counter);
                        counter.rollOff(bucketToRollOff);
                        if (counter.total > 0) {
                            ranking.add(counter);
                        } else {
                            counters.remove(counter.fingerprint);
                        }
                    }
                }
            }
        }

        /** Top K fingerprints by count in the last 24h window. */
        public List<Map.Entry<String, Long>> getTopK(int k) {
            synchronized (lock) {
                List<Map.Entry<String, Long>> result = new ArrayList<>(k);
                Iterator<SlidingCounter> it = ranking.iterator();
                while (it.hasNext() && result.size() < k) {
                    SlidingCounter c = it.next();
                    result.add(Map.entry(c.fingerprint, c.total));
                }
                return result;
            }
        }

        public long totalFingerprints() {
            return counters.size();
        }
    }

    // ─── Demo ───

    public static void main(String[] args) {
        TopKService service = new TopKService();

        // Simulate some events. Fingerprints are "svc:exceptionClass:method"
        long now = System.currentTimeMillis() / 1000;

        // NullPointerException in auth service — very frequent
        for (int i = 0; i < 500; i++) {
            service.recordException("auth:NullPointerException:login", now - i);
        }

        // Timeout in payment service — moderately frequent
        for (int i = 0; i < 150; i++) {
            service.recordException("payment:TimeoutException:charge", now - i);
        }

        // Validation error in user service — rare but there
        for (int i = 0; i < 40; i++) {
            service.recordException("user:ValidationException:update", now - i);
        }

        // Random long tail
        for (int i = 0; i < 100; i++) {
            service.recordException("svc-" + i + ":Generic:handle", now - i);
        }

        System.out.println("Total fingerprints tracked: " + service.totalFingerprints());
        System.out.println();

        // Query top 5
        System.out.println("=== Top 5 exceptions in last 24h ===");
        List<Map.Entry<String, Long>> top = service.getTopK(5);
        int rank = 1;
        for (Map.Entry<String, Long> entry : top) {
            System.out.printf("  %d. %-50s  %d occurrences%n",
                rank++, entry.getKey(), entry.getValue());
        }
        System.out.println();

        // Simulate window advance — roll off bucket from 24h ago
        System.out.println("=== Simulating 1-minute tick ===");
        // We advance to a bucket that has events and reset it
        service.tick(now);
        System.out.println("After tick, total fingerprints: " + service.totalFingerprints());
        System.out.println();

        System.out.println("=== Top 5 after window advance ===");
        for (Map.Entry<String, Long> entry : service.getTopK(5)) {
            System.out.printf("  %-50s  %d%n", entry.getKey(), entry.getValue());
        }

        // Large-scale sanity: push 10K distinct fingerprints
        System.out.println("\n=== Scale test: 10K fingerprints, 100K events ===");
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            String fp = "fp-" + (i % 10_000);  // 10K distinct
            service.recordException(fp, now);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("Ingested 100K events in " + elapsedMs + "ms");
        System.out.println("Total fingerprints now: " + service.totalFingerprints());
        System.out.println();

        // Top 10 after scale test
        start = System.nanoTime();
        List<Map.Entry<String, Long>> top10 = service.getTopK(10);
        long queryMicros = (System.nanoTime() - start) / 1_000;
        System.out.println("getTopK(10) took " + queryMicros + "µs");
        for (Map.Entry<String, Long> entry : top10) {
            System.out.printf("  %-50s  %d%n", entry.getKey(), entry.getValue());
        }
    }
}
