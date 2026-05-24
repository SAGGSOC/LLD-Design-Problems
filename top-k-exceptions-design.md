# Top-K Exceptions in a 24h Window — System Design

---

## 1. Requirements

### Functional
- Services across the org emit exceptions (stack trace, error class, service name, timestamp)
- Dashboard queries: "top K exceptions in the last 24 hours, globally or per service"
- Fresh data (updates visible within ~1 min)
- Drill-down: click an exception to see recent occurrences / sample stack traces

### Non-Functional

| Requirement | Target |
|---|---|
| Services emitting | 10K+ services |
| Exception events/sec (peak) | 500K-1M events/sec |
| Query latency (top-K) | < 100ms p99 |
| Data freshness | ≤ 1 min |
| Retention | 7 days hot, 90 days cold |
| Accuracy | Approximate OK for bulk tail; exact top-100 must be correct |

### Clarifying Questions

| Question | Assumed |
|---|---|
| What defines "same exception"? | (serviceName, exceptionClass, topFrame) — a stable fingerprint |
| Exact counts or approximate? | Exact top-100, approximate for tail — use heavy hitters algorithm |
| Global top-K only, or per-service? | Both — per-service is primary, global is aggregated |
| Sliding window or tumbling? | Sliding 24h — "last 24 hours from now" |
| K values supported? | K ≤ 1000 (typical dashboard shows 10-100) |

---

## 2. High-Level Architecture

```
┌────────────┐   ┌────────────┐   ┌────────────┐
│ Service A  │   │ Service B  │   │ Service N  │
│ Agent/SDK  │   │ Agent/SDK  │   │ Agent/SDK  │
└─────┬──────┘   └─────┬──────┘   └─────┬──────┘
      │                │                 │
      └────────────────┴─────────────────┘
                       │  (batch + async)
                       ▼
           ┌────────────────────────┐
           │     Ingestion API       │
           │   (HTTP/gRPC + auth)   │
           └───────────┬────────────┘
                       │
                       ▼
           ┌────────────────────────┐
           │          Kafka           │
           │   topic: exceptions     │
           │   partitioned by        │
           │   fingerprint hash      │
           └──┬──────────────────┬──┘
              │                  │
              ▼                  ▼
    ┌─────────────────┐  ┌─────────────────┐
    │  Stream Proc    │  │  Archive        │
    │  (Flink/Spark)  │  │  Consumer       │
    │                 │  │ → S3 (Parquet)  │
    │ per-fingerprint │  └─────────────────┘
    │ rolling counts  │
    └────┬────────────┘
         │
         ▼
    ┌─────────────────────────┐
    │    Redis Cluster         │
    │  - per-fingerprint       │
    │    1-min buckets (HLL    │
    │    or sorted set)        │
    │  - per-service top-K     │
    │    sorted sets           │
    └────┬────────────────────┘
         │
         ▼
    ┌─────────────────────────┐
    │    Query API            │
    │  /top-k?window=24h&k=50 │
    └─────────────────────────┘
```

---

## 3. Exception Fingerprinting

```
Fingerprint = hash(serviceName + exceptionClass + topStackFrame)

Raw:
  NullPointerException at UserService.lookup(UserService.java:42)
     caused by ... (deep frames vary per call)

Fingerprint:
  "svc-auth:NullPointerException:UserService.lookup:42"
  → SHA-256 → fp-abc123

Why this matters:
  - Deep stack frames vary (different callers → different full stacks)
  - Top frame + exception class is the stable identity of a bug
  - Hundreds of thousands of distinct raw stacks collapse to thousands of fingerprints
```

---

## 4. Core Counting Strategy — Bucketed Sliding Window

The 24h window slides continuously. Naive "keep timestamped events for 24h" is too expensive at 1M/sec.

**Approach: 1-minute buckets + rolling aggregation**

```
Per fingerprint, keep an array of 1440 counters (one per minute of a day):

  fp-abc123:
    minute 0:   45    ← 24h ago
    minute 1:   52
    ...
    minute 1439: 38   ← current minute

  total_24h = sum(all buckets) = incremental maintenance

  Every minute:
    - Roll off oldest bucket: total -= buckets[oldest]
    - Reset oldest bucket for new current minute
    - Increment current bucket on new events
```

Memory per fingerprint: 1440 × 4 bytes = ~5.7 KB
For 100K distinct fingerprints: ~570 MB — fits comfortably in Redis

### Redis representation

```
Key: count:{fingerprint}
Value: Hash with fields "m0", "m1", ... "m1439"
  HINCRBY count:fp-abc123 m742 1    (bucket for minute 742)
  HDEL   count:fp-abc123 m742       (when rolling off)

Also maintain a total:
  GET/SET total:{fingerprint}
  Increment atomically alongside the bucket.
```

### Sliding top-K per service

```
Redis sorted set, one per service, score = total count over 24h:
  ZADD topk:service:{svc}  {count}  {fingerprint}
  ZREVRANGE topk:service:{svc} 0 99  → top-100 fingerprints

Updated by the stream processor every minute:
  For each fingerprint whose count changed:
    ZADD topk:service:svc-auth  <new-total>  <fp>
```

---

## 5. Trade-offs: Exact vs Approximate

Pure exact counting becomes expensive at extreme cardinality (tens of millions of distinct fingerprints, rare in practice but possible). Two mitigations:

### (a) Count-Min Sketch for the long tail

- Keep exact counters for fingerprints we've "seen" (in a top-N hot set)
- Use Count-Min Sketch for everything else
- Trade ~1% count error for O(sqrt(N)) memory

### (b) Space-Saving Algorithm (Metwally et al.)

- Maintains top-K with bounded memory (K + ε slots)
- Strong guarantees: no false negatives in top-K if item frequency > 1/(K+ε)
- Best when you only care about top-K, not all counts

For this problem, **exact counting with 1-min buckets** is almost always good enough at realistic cardinality. Mention these as extensions if pressed on scale.

---

## 6. Query Path

```
GET /top-k?service=svc-auth&window=24h&k=50
     │
     ▼
┌────────────────────────────────┐
│ Query API                      │
│                                │
│ 1. ZREVRANGE topk:service:{svc}│
│    0 49 WITHSCORES             │
│    → [(fp, count), ...]        │
│                                │
│ 2. Enrich: MGET meta:{fp} for  │
│    each fingerprint to get     │
│    exception class, sample     │
│    stack, last-seen timestamp  │
│                                │
│ 3. Return paginated result     │
└────────────────────────────────┘

Latency: ~5-10ms (all Redis ops, pipelined)
```

---

## 7. Ingestion Path

```
Service Agent batches 100 exceptions or 1s worth →
  POST /ingest with auth token →
    Ingestion API writes to Kafka (partition = hash(fingerprint) mod N) →
      Stream processor consumes, per fingerprint:
        - HINCRBY count:{fp} m{currentMinute}  1
        - INCR total:{fp}
        - ZADD topk:service:{svc}  <current-total>  <fp>
      Archive consumer writes raw event to S3 for forensics
```

Throughput:
- Kafka: 1M events/sec is easily handled with 32-64 partitions
- Stream processor: one consumer per partition, stateful keyed by fingerprint
- Redis write amplification: 2-3 ops per event → 2-3M Redis ops/sec needs a sharded cluster (~8-16 shards)

---

## 8. Cleanup Job (the rolling window)

```
Every minute, a scheduler:
  current_minute = minute_of_day(now)
  oldest_minute  = (current_minute + 1) % 1440

  For each hot fingerprint:
    bucket_count = HGET count:{fp} m{oldest_minute}
    if bucket_count > 0:
      HINCRBY total:{fp} -bucket_count     (subtract from rolling total)
      HDEL    count:{fp} m{oldest_minute}
      ZADD    topk:service:{svc}  <new-total>  {fp}

  If total:{fp} == 0:
    Remove fingerprint entirely (exception not seen in 24h)
```

Runs in a Lambda/cron every minute. Processes only fingerprints that actually had events 24h ago — for most, no-op.

---

## 9. Failure Modes

| Failure | Impact | Mitigation |
|---|---|---|
| Ingestion API down | Services retry, agent has local buffer | Agent persists queue to disk, bounded at 100MB per service |
| Kafka partition unavailable | Lose data for some fingerprints | Multi-AZ replication, producer retries |
| Redis primary fails | Stale reads / writes | Redis Sentinel + replicas, RDB snapshots every 15min |
| Stream processor crashes | Window counts become stale | Checkpoint offsets in Kafka, resume; backfill by replaying up to consumer lag window |
| Counter drift | Bucket cleanup misses minute | Periodic re-aggregation job reconciles total from bucket sum |

---

## 10. What NOT to do

- **Don't store per-event data in Redis** — too much memory, no aggregation benefit
- **Don't query raw events at read time** — latency dies above a few thousand events
- **Don't use SQL aggregation with window functions** — great for ad-hoc, fails at 1M/sec ingestion
- **Don't keep sub-second buckets** — buys no freshness benefit over 1-min, costs 60× memory

---

## 11. Summary: what makes this a good answer

1. **Fingerprinting** — the interviewer wants you to realize that raw stacks are too cardinal and need canonicalization
2. **Sliding-window bucketization** — 1-min buckets + rolling sum is the standard trick for time-decayed counters
3. **Kafka for decoupling** — lets ingestion absorb bursts without backpressure on services
4. **Redis sorted sets** for the hot top-K — Redis's `ZREVRANGE` gives you top-K in O(log N + K)
5. **Approximate as a fallback** — Count-Min Sketch or Space-Saving for long-tail scale, but exact is usually fine
6. **Cold archive to S3** — separates hot operational queries from forensics / longer retention
