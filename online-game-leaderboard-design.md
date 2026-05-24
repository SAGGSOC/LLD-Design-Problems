# Online Game Leaderboard — System Design

---

## 1. Problem Statement & Requirements

### Functional Requirements
- `submitScore(playerId, score)` — player submits a new score
- `getTopK(k)` — get top K players globally (e.g., top 100)
- `getPlayerRank(playerId)` — get a specific player's rank
- `getAroundMe(playerId, range)` — get players ranked near a given player (e.g., ±5)
- Scores update in near real-time
- Support multiple leaderboards (daily, weekly, all-time)

### Non-Functional Requirements
- Scale: 50M+ players, 100K+ concurrent score submissions
- Read-heavy: rank lookups >> score submissions (~100:1)
- Low latency: top-K and rank queries < 50ms
- Availability > Consistency (eventual consistency is acceptable for a game leaderboard)
- Scores are monotonically tracked (keep highest score per player)

### Clarifying Questions to Ask in Interview
- "Is it one global leaderboard or per-game/per-region?" → Start global, extend later
- "Do we keep the highest score or the latest?" → Highest
- "How many players?" → 50M+
- "Real-time or batch updated?" → Near real-time
- "Do we need historical leaderboards (daily/weekly reset)?" → Yes

---

## 2. High-Level Design

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────────────┐
│   Game       │     │   API Gateway /   │     │   Leaderboard        │
│   Clients    │────▶│   Load Balancer   │────▶│   Service            │
│              │     │                    │     │   (Stateless)        │
└──────────────┘     └──────────────────┘     └──────────┬───────────┘
                                                          │
                          ┌───────────────────────────────┼──────────────────┐
                          │                               │                  │
                          ▼                               ▼                  ▼
                   ┌─────────────┐              ┌──────────────┐    ┌──────────────┐
                   │  Redis      │              │  Player DB   │    │  Score        │
                   │  Sorted Set │              │  (DynamoDB/  │    │  History DB   │
                   │  (Hot Path) │              │   Postgres)  │    │  (Cold Store) │
                   └─────────────┘              └──────────────┘    └──────────────┘
```

### Component Responsibilities

| Component | Role |
|---|---|
| API Gateway / LB | Rate limiting, auth, routing |
| Leaderboard Service | Stateless business logic, score validation, deduplication |
| Redis Sorted Set | Real-time ranking engine (hot path for all rank queries) |
| Player DB | Player profiles, metadata (name, avatar, region) |
| Score History DB | Audit trail, historical scores, analytics |

---

## 3. Why Redis Sorted Set?

This is the core data structure choice and the most important part of the design.

A Redis Sorted Set (ZSET) is a collection where each member has a score, and members are automatically ordered by score.

```
ZADD leaderboard 1500 "player:42"
ZADD leaderboard 2300 "player:7"
ZADD leaderboard 1800 "player:99"

Internal ordering (descending):
  Rank 1: player:7   → 2300
  Rank 2: player:99  → 1800
  Rank 3: player:42  → 1500
```

### Operation Complexity

| Operation | Redis Command | Time Complexity |
|---|---|---|
| Submit/update score | `ZADD` | O(log N) |
| Get top K | `ZREVRANGE 0 K-1 WITHSCORES` | O(log N + K) |
| Get player rank | `ZREVRANK playerId` | O(log N) |
| Get around me (±5) | `ZREVRANGE rank-5 rank+5 WITHSCORES` | O(log N + range) |
| Get player score | `ZSCORE playerId` | O(1) |
| Total players | `ZCARD` | O(1) |

For 50M players, log₂(50M) ≈ 26 operations internally. This is extremely fast.

### Why Not Alternatives?

| Alternative | Problem |
|---|---|
| SQL `ORDER BY score DESC LIMIT K` | Full table scan or index scan on every query. Doesn't scale to 50M rows at low latency. |
| In-memory TreeMap/BST in app server | Not shared across instances. Lost on restart. Can't scale horizontally. |
| DynamoDB with GSI on score | GSI doesn't support efficient rank queries. No "what's my rank?" in O(log N). |
| Custom skip list | Reinventing Redis. Operational burden. |

Redis Sorted Set is purpose-built for this problem.

---

## 4. API Design

```
POST /scores
  Body: { "playerId": "p42", "score": 2500, "gameId": "g1" }
  Response: { "accepted": true, "newRank": 7 }

GET /leaderboard/top?k=100&period=weekly
  Response: {
    "entries": [
      { "rank": 1, "playerId": "p7",  "name": "Alice", "score": 9800 },
      { "rank": 2, "playerId": "p99", "name": "Bob",   "score": 9500 },
      ...
    ]
  }

GET /leaderboard/rank?playerId=p42&period=alltime
  Response: { "playerId": "p42", "rank": 1523, "score": 2500 }

GET /leaderboard/around?playerId=p42&range=5&period=weekly
  Response: {
    "entries": [
      { "rank": 1520, "playerId": "p10", "score": 2520 },
      { "rank": 1521, "playerId": "p33", "score": 2510 },
      { "rank": 1522, "playerId": "p88", "score": 2505 },
      { "rank": 1523, "playerId": "p42", "score": 2500 },  ← you
      { "rank": 1524, "playerId": "p55", "score": 2495 },
      ...
    ]
  }
```

---

## 5. Detailed Data Flow

### Submit Score

```
Client → API Gateway → Leaderboard Service:

1. Validate: Is score within valid range? Is playerId real?
2. Read current score:  ZSCORE leaderboard:alltime player:42
3. Compare: if newScore > currentScore (keep highest only)
4. Write to Redis:      ZADD leaderboard:alltime 2500 player:42
                        ZADD leaderboard:weekly  2500 player:42
                        ZADD leaderboard:daily   2500 player:42
5. Async: Write to Score History DB (Kafka → Consumer → DB)
6. Return new rank:     ZREVRANK leaderboard:alltime player:42
```

### Get Top K

```
Client → API Gateway → Leaderboard Service:

1. ZREVRANGE leaderboard:weekly 0 99 WITHSCORES
   → Returns [(player:7, 9800), (player:99, 9500), ...]

2. Batch fetch player names from Player DB (or cache)
   → MGET player:7:name player:99:name ...

3. Merge and return
```

### Get Player Rank

```
Client → API Gateway → Leaderboard Service:

1. ZREVRANK leaderboard:alltime player:42  → 1522 (0-indexed)
2. ZSCORE  leaderboard:alltime player:42   → 2500
3. Return { rank: 1523, score: 2500 }      (1-indexed for display)
```

---

## 6. Handling Multiple Time Periods

Maintain separate sorted sets per period:

```
leaderboard:alltime    — never reset
leaderboard:weekly     — reset every Monday 00:00 UTC
leaderboard:daily      — reset every day 00:00 UTC
```

### Reset Strategy

**Option A — Delete and recreate (simple):**
```
DEL leaderboard:daily
```
Cheap for daily. All players start fresh.

**Option B — Key rotation with TTL (zero-downtime):**
```
leaderboard:daily:2026-03-13  ← today's board
leaderboard:daily:2026-03-12  ← yesterday's (TTL = 48h, auto-expires)
```
Reads always go to today's key. No downtime during rotation.

**Option C — Scheduled job (weekly):**
A cron job at Monday 00:00 UTC:
1. Archive `leaderboard:weekly` to cold storage
2. `DEL leaderboard:weekly`

---

## 7. Scaling Redis

### Single Redis Instance Limits
- A single Redis ZSET can hold ~2^32 members (~4 billion)
- 50M members with 8-byte score + 20-byte key ≈ ~1.5 GB — fits in memory easily
- Single-threaded Redis handles ~100K ops/sec for ZADD/ZREVRANGE

For 100K concurrent writes, a single Redis instance is borderline. Options:

### Option A — Redis Cluster with Application-Level Sharding

Shard by game or region, not by player:
```
Redis Node 1: leaderboard:game1:alltime, leaderboard:game1:weekly
Redis Node 2: leaderboard:game2:alltime, leaderboard:game2:weekly
```
Each game's leaderboard stays on one node (sorted set can't be split across nodes).

### Option B — Read Replicas

```
         ┌──────────┐
Writes → │  Primary  │
         └────┬─────┘
              │ replication
     ┌────────┼────────┐
     ▼        ▼        ▼
  Replica1  Replica2  Replica3  ← all reads go here
```

Writes go to primary. Reads (top-K, rank queries) go to replicas. Since reads >> writes (100:1), this scales read throughput linearly.

### Option C — Hybrid for Global Leaderboard at Extreme Scale

If a single sorted set can't handle the write throughput:
1. Shard players into N buckets by `hash(playerId) % N`
2. Each shard maintains its own sorted set
3. A merge service periodically combines top entries from all shards into a "global top-K" cache
4. Exact rank queries become approximate (acceptable for games)

---

## 8. Low-Level Design

### Class Diagram

```
┌──────────────────────────────────────┐
│         LeaderboardService            │
│──────────────────────────────────────│
│ - redisClient: RedisClient            │
│ - playerService: PlayerService        │
│ - scoreValidator: ScoreValidator      │
│ - eventPublisher: EventPublisher      │
│──────────────────────────────────────│
│ + submitScore(playerId, score, game)  │
│ + getTopK(k, period): List<Entry>     │
│ + getPlayerRank(playerId, period)     │
│ + getAroundMe(playerId, range, period)│
└──────────────────────────────────────┘

┌──────────────────────────────────────┐
│         RedisLeaderboardRepo          │
│──────────────────────────────────────│
│ + zadd(key, score, member): void      │
│ + zrevrange(key, start, stop): List   │
│ + zrevrank(key, member): Long         │
│ + zscore(key, member): Double         │
│ + del(key): void                      │
└──────────────────────────────────────┘

┌──────────────────────────────────────┐
│         PlayerService                 │
│──────────────────────────────────────│
│ + getPlayer(playerId): Player         │
│ + getPlayersBatch(ids): List<Player>  │
└──────────────────────────────────────┘

┌──────────────────────────────────────┐
│         ScoreValidator                │
│──────────────────────────────────────│
│ + validate(playerId, score): boolean  │
│   (anti-cheat, range check, etc.)     │
└──────────────────────────────────────┘

┌──────────────────────────────────────┐
│         EventPublisher                │
│──────────────────────────────────────│
│ + publish(ScoreEvent): void           │
│   (Kafka → Score History Consumer)    │
└──────────────────────────────────────┘
```

### Database Schemas

**Player DB (DynamoDB or Postgres):**
```
Players:
  playerId    (PK)  | name     | avatar_url | region | created_at
  "p42"             | "Alice"  | "..."      | "US"   | 2025-01-15
```

**Score History (DynamoDB or append-only Postgres):**
```
ScoreHistory:
  playerId (PK) | timestamp (SK) | score | gameId | leaderboard_period
  "p42"         | 1710345600     | 2500  | "g1"   | "alltime"
```

### Pseudocode: Submit Score

```python
def submit_score(player_id, score, game_id):
    # 1. Validate
    if not score_validator.validate(player_id, score):
        raise InvalidScoreError("Score rejected by anti-cheat")

    # 2. Check current score (keep highest)
    current = redis.zscore(f"lb:{game_id}:alltime", player_id)
    if current is not None and score <= current:
        return {"accepted": False, "reason": "Not a new high score"}

    # 3. Update all period leaderboards
    for period in ["alltime", "weekly", "daily"]:
        key = f"lb:{game_id}:{period}"
        # ZADD with GT flag: only update if new score > current
        redis.zadd(key, {player_id: score}, gt=True)

    # 4. Async: publish to score history
    event_publisher.publish(ScoreEvent(player_id, score, game_id, now()))

    # 5. Return new rank
    rank = redis.zrevrank(f"lb:{game_id}:alltime", player_id)
    return {"accepted": True, "newRank": rank + 1}
```

### Pseudocode: Get Around Me

```python
def get_around_me(player_id, game_id, period, range_size=5):
    key = f"lb:{game_id}:{period}"

    # 1. Get player's rank
    rank = redis.zrevrank(key, player_id)
    if rank is None:
        raise PlayerNotFoundError()

    # 2. Calculate window
    start = max(0, rank - range_size)
    stop = rank + range_size

    # 3. Fetch range with scores
    entries = redis.zrevrange(key, start, stop, withscores=True)
    # entries = [("p10", 2520), ("p33", 2510), ("p42", 2500), ...]

    # 4. Batch fetch player names
    player_ids = [e[0] for e in entries]
    players = player_service.get_players_batch(player_ids)

    # 5. Build response
    result = []
    for i, (pid, score) in enumerate(entries):
        result.append({
            "rank": start + i + 1,
            "playerId": pid,
            "name": players[pid].name,
            "score": score
        })
    return result
```

---

## 9. Anti-Cheat Considerations

| Technique | How |
|---|---|
| Server-side score validation | Game server computes score, not client. Client only sends game events. |
| Rate limiting | Max N score submissions per player per minute |
| Score range checks | Reject scores outside physically possible range |
| Anomaly detection | Flag players with sudden score jumps (async ML pipeline) |
| Replay verification | Store game replay data, verify score matches replay |

---

## 10. Caching Strategy

```
┌─────────────┐     ┌──────────────┐     ┌───────────┐
│  Client      │────▶│  CDN / Edge  │────▶│  Service  │
│              │     │  Cache       │     │           │
└─────────────┘     └──────────────┘     └───────────┘
```

- Top-100 leaderboard: Cache at CDN/edge with 5-10 second TTL. Extremely hot data.
- Player rank: Cache in application-level cache (local or Redis) with 1-2 second TTL.
- Player profiles: Cache with longer TTL (5 min). Names don't change often.

---

## 11. Interview Walkthrough Pacing

### Step 1: Requirements (3 min)
- Clarify: single game or multi-game, time periods, scale, read/write ratio
- State assumptions explicitly

### Step 2: HLD (10 min)
- Draw the architecture diagram
- Introduce Redis Sorted Set as the core ranking engine
- Explain why Redis ZSET over SQL or custom data structures
- Show the data flow for submitScore and getTopK

### Step 3: Deep Dives (15 min)
- Multiple time periods (key rotation)
- Scaling Redis (replicas, sharding by game)
- Anti-cheat validation
- Around-me query implementation
- Caching hot data at edge

### Step 4: Trade-offs (5 min)
- Availability vs consistency (eventual consistency is fine for games)
- Memory vs accuracy (approximate rank at extreme scale)
- Real-time vs batched updates (real-time for competitive games)

---

## 12. Complexity Summary

| Operation | Latency | Complexity |
|---|---|---|
| Submit score | < 5ms (Redis) | O(log N) |
| Get top K | < 10ms | O(log N + K) |
| Get player rank | < 5ms | O(log N) |
| Get around me | < 10ms | O(log N + range) |
| Daily/weekly reset | < 1s | O(1) with DEL |

For N = 50M players, log₂(50M) ≈ 26. All operations are sub-millisecond at the Redis level. Network round-trip dominates.

---

## 13. Extensions (If Interviewer Asks)

| Extension | Approach |
|---|---|
| Regional leaderboards | Separate ZSET per region: `lb:game1:US:weekly` |
| Friend leaderboard | Maintain per-player friend set. On query, `ZSCORE` each friend and sort client-side (small N). |
| Leaderboard with ties | Use composite score: `score * 1e10 + (MAX_TIMESTAMP - timestamp)`. Earlier submission ranks higher on tie. |
| Real-time push updates | WebSocket connection. Publish rank changes via Redis Pub/Sub → WebSocket server → client. |
| Historical snapshots | Periodic cron dumps ZSET to cold storage (S3/DynamoDB). Query historical boards from there. |

---

## 14. At-Least-Once Delivery with Deduplication

In a distributed system, network failures, retries, and service restarts mean score submissions can arrive more than once. We need at-least-once delivery (never lose a score) combined with deduplication (never double-count a score).

### The Problem

```
Client → API Gateway → Leaderboard Service → Redis
                  ↑                              │
                  │    timeout, no ACK received   │
                  └──────── client retries ───────┘

Result: Same score submitted twice. If we're tracking "latest score" or "cumulative score",
this causes corruption. For "highest score" with ZADD GT, duplicates are harmless for the
global board — but they still cause duplicate Kafka events, duplicate history rows,
and duplicate fan-out to friends leaderboards.
```

### Solution: Idempotency Key + Dedup Store

```
┌──────────┐     ┌──────────────────┐     ┌──────────────┐     ┌───────────┐
│  Client   │────▶│  Leaderboard     │────▶│  Dedup Check │────▶│  Redis    │
│           │     │  Service         │     │  (Redis SET) │     │  ZSET     │
└──────────┘     └──────────────────┘     └──────────────┘     └───────────┘
```

**Step 1: Client generates an idempotency key per score event**

```
POST /scores
Headers: { "Idempotency-Key": "score-p42-g1-1710345600-uuid4" }
Body:    { "playerId": "p42", "score": 2500, "gameId": "g1" }
```

**Step 2: Service checks dedup store before processing**

```python
def submit_score(player_id, score, game_id, idempotency_key):
    # 1. Dedup check: SET NX with TTL (atomic check-and-set)
    already_processed = redis.set(
        f"dedup:{idempotency_key}",
        "1",
        nx=True,       # only set if not exists
        ex=3600        # TTL 1 hour (covers retry window)
    )

    if not already_processed:
        # This exact request was already processed
        return {"accepted": True, "deduplicated": True}

    # 2. Proceed with normal flow
    redis.zadd(f"lb:{game_id}:alltime", {player_id: score}, gt=True)
    event_publisher.publish(ScoreEvent(player_id, score, game_id, idempotency_key))

    return {"accepted": True, "deduplicated": False}
```

**Step 3: Kafka consumer also deduplicates**

```python
def consume_score_event(event):
    # Consumer-side dedup (handles Kafka redelivery after consumer crash)
    if score_history_db.exists(event.idempotency_key):
        return  # already persisted, skip

    score_history_db.insert(
        player_id=event.player_id,
        score=event.score,
        idempotency_key=event.idempotency_key,  # unique constraint
        timestamp=event.timestamp
    )
```

### Why Two Layers of Dedup?

| Layer | Protects Against |
|---|---|
| Redis `SET NX` at API level | Client retries, load balancer retries, duplicate HTTP requests |
| DB unique constraint at consumer level | Kafka redelivery after consumer crash (at-least-once Kafka semantics) |

### Dedup Key TTL Strategy

- TTL = 1 hour covers any reasonable retry window
- After TTL expires, the key is gone — but that's fine because a retry after 1 hour is a new request, not a duplicate
- Memory cost: ~100 bytes per key × 100K submissions/sec × 3600s = ~36 GB at peak. Use a dedicated Redis instance or Redis Cluster for dedup keys, separate from the leaderboard ZSET

---

## 15. Friends Leaderboard — Scaling from Small to Large

### Tier 1: Small Friend Lists (< 100 friends) — On-Demand Fetch

When a user has tens of friends, compute the leaderboard at read time. No precomputation needed.

```
┌──────────┐     ┌──────────────────┐     ┌───────────────┐     ┌───────────┐
│  Client   │────▶│  Leaderboard     │────▶│  Friend       │────▶│  Redis    │
│           │     │  Service         │     │  Service      │     │  Pipeline │
└──────────┘     └──────────────────┘     └───────────────┘     └───────────┘
```

```python
def get_friends_leaderboard(player_id, game_id, period):
    # 1. Get friend list (from social graph DB or cache)
    friend_ids = friend_service.get_friends(player_id)  # e.g., 50 friends

    # 2. Pipeline ZSCORE calls to Redis (single round-trip)
    pipe = redis.pipeline()
    key = f"lb:{game_id}:{period}"
    for fid in friend_ids + [player_id]:  # include self
        pipe.zscore(key, fid)
    scores = pipe.execute()

    # 3. Zip, filter nulls, sort in memory
    entries = []
    all_ids = friend_ids + [player_id]
    for fid, score in zip(all_ids, scores):
        if score is not None:
            entries.append({"playerId": fid, "score": score})

    entries.sort(key=lambda e: e["score"], reverse=True)

    # 4. Add ranks and player names
    player_names = player_service.get_players_batch([e["playerId"] for e in entries])
    for i, entry in enumerate(entries):
        entry["rank"] = i + 1
        entry["name"] = player_names[entry["playerId"]].name

    return entries
```

**Performance analysis for 50 friends:**
- 1 round-trip to get friend list: ~2ms (cached)
- 1 Redis pipeline with 51 ZSCORE commands: ~1-2ms (pipelined = single round-trip)
- In-memory sort of 51 items: < 0.01ms
- 1 batch player name fetch: ~2-3ms (cached)
- Total: ~5-7ms. Well within 50ms budget.

**Why this works for small lists:**
- Redis pipeline batches all ZSCORE calls into a single network round-trip
- Sorting 50-100 items in memory is negligible
- No write amplification — no fan-out on score updates
- No stale data — always reads latest scores

### Tier 2: Large Friend Lists (100-1000+ friends) — Precomputed Cache with Fan-Out

When users have hundreds or thousands of friends, pipelining 1000+ ZSCORE calls per read becomes expensive, especially under high QPS. Switch to precomputed cached leaderboards.

```
Score Update Flow (Write Path):
┌──────────┐     ┌──────────────────┐     ┌───────────────┐     ┌────────────────────┐
│  Player   │────▶│  Score Update    │────▶│  Fan-Out      │────▶│  Friends' Cached   │
│  submits  │     │  (Redis ZADD)    │     │  Worker       │     │  Leaderboards      │
│  score    │     │                  │     │  (async)      │     │  (Redis ZSETs)     │
└──────────┘     └──────────────────┘     └───────────────┘     └────────────────────┘

Read Path:
┌──────────┐     ┌──────────────────┐     ┌────────────────────┐
│  Client   │────▶│  Leaderboard     │────▶│  friends:lb:p42    │  ← precomputed ZSET
│  reads    │     │  Service         │     │  (already sorted)  │
└──────────┘     └──────────────────┘     └────────────────────┘
```

**Data model: Per-user friends leaderboard ZSET**

```
Key: friends:lb:{playerId}:{gameId}:{period}

friends:lb:p42:g1:weekly = {
    "p7":  9800,
    "p99": 9500,
    "p42": 2500,   ← self
    "p33": 2100,
    ...
}
```

**Write path: Fan-out on score update**

```python
def on_score_update(player_id, new_score, game_id):
    # 1. Update global leaderboard (as before)
    redis.zadd(f"lb:{game_id}:alltime", {player_id: new_score}, gt=True)

    # 2. Get everyone who has this player as a friend (reverse lookup)
    followers = friend_service.get_followers(player_id)  # who friended me

    # 3. Fan-out: update this player's score in each follower's cached board
    pipe = redis.pipeline()
    for follower_id in followers:
        key = f"friends:lb:{follower_id}:{game_id}:weekly"
        pipe.zadd(key, {player_id: new_score}, gt=True)
    pipe.execute()
```

**Read path: Direct ZSET read (precomputed)**

```python
def get_friends_leaderboard_cached(player_id, game_id, period):
    key = f"friends:lb:{player_id}:{game_id}:{period}"

    # Single Redis call — already sorted
    entries = redis.zrevrange(key, 0, 49, withscores=True)  # top 50 friends

    player_ids = [e[0] for e in entries]
    players = player_service.get_players_batch(player_ids)

    return [
        {"rank": i+1, "playerId": pid, "name": players[pid].name, "score": score}
        for i, (pid, score) in enumerate(entries)
    ]
```

**Read latency: ~2-3ms** (single ZREVRANGE + cached name lookup). Excellent.

### Fan-Out Cost Analysis

| Metric | Value |
|---|---|
| Average friends per user | 200 |
| Score updates per second | 5,000 |
| Fan-out writes per second | 5,000 × 200 = 1M ZADD/sec |
| Redis pipeline batching | ~50 ZADDs per pipeline call |
| Actual Redis round-trips/sec | ~20K |

1M ZADD/sec is achievable with a Redis Cluster (3-5 shards). The fan-out is async via a worker pool, so it doesn't block the score submission response.

### Hybrid Strategy: Adaptive Tier Selection

```python
SMALL_THRESHOLD = 100

def get_friends_leaderboard(player_id, game_id, period):
    friend_count = friend_service.get_friend_count(player_id)

    if friend_count <= SMALL_THRESHOLD:
        return get_friends_leaderboard_on_demand(player_id, game_id, period)
    else:
        return get_friends_leaderboard_cached(player_id, game_id, period)
```

---

## 16. Cross-Shard Challenges — Deep Dive

When the global leaderboard is sharded by `hash(playerId)`, several operations become non-trivial.

### Problem 1: "Around Me" Query Across Shards

With user-based sharding, players with adjacent scores live on different shards. You can't do a single `ZREVRANGE` to get ±20 surrounding players.

**Solution: Score-based global index (separate from user shards)**

```
User Shards (for write throughput):          Global Rank Index (for rank queries):
┌─────────────┐  ┌─────────────┐            ┌──────────────────────────┐
│ Shard 0     │  │ Shard 1     │            │  lb:global:alltime       │
│ hash%4 == 0 │  │ hash%4 == 1 │  ────────▶ │  (single ZSET on        │
│ p42: 2500   │  │ p7: 9800    │  fan-in    │   dedicated high-mem     │
│ p88: 1200   │  │ p33: 2100   │            │   Redis instance)        │
└─────────────┘  └─────────────┘            │  p7:  9800               │
┌─────────────┐  ┌─────────────┐            │  p99: 9500               │
│ Shard 2     │  │ Shard 3     │            │  p42: 2500               │
│ hash%4 == 2 │  │ hash%4 == 3 │            │  p33: 2100               │
│ p99: 9500   │  │ p55: 1800   │            │  ...                     │
│ p10: 3000   │  │ p77: 900    │            └──────────────────────────┘
└─────────────┘  └─────────────┘
```

**Write path:** Every score update writes to BOTH the user shard AND the global rank index.
**Read path for "around me":** Query the global rank index directly — single ZREVRANGE.

**Trade-off:** The global index is a write bottleneck (all writes funnel to one ZSET). Mitigate with:
- Read replicas for the global index (reads are 100:1 vs writes)
- Async updates to global index (1-2 second staleness acceptable for "around me")
- The global index only needs the score, not full player data

### Problem 2: Cross-Shard Friends Leaderboard (1000+ friends across all shards)

With user-based sharding, a user's 1000 friends are scattered across all shards.

**On-demand approach (Tier 1) across shards:**

```python
def get_friends_leaderboard_sharded(player_id, game_id, period):
    friend_ids = friend_service.get_friends(player_id)  # 1000 friends

    # Group friends by shard
    shard_groups = defaultdict(list)
    for fid in friend_ids:
        shard = hash(fid) % NUM_SHARDS
        shard_groups[shard].append(fid)

    # Parallel pipeline to each shard
    all_scores = {}
    futures = []
    for shard_id, fids in shard_groups.items():
        # Each shard gets its own pipeline (parallel execution)
        futures.append(
            executor.submit(fetch_scores_from_shard, shard_id, fids, game_id, period)
        )

    for future in futures:
        all_scores.update(future.result())

    # Sort in memory
    entries = sorted(all_scores.items(), key=lambda x: x[1], reverse=True)
    return entries[:50]  # top 50 friends
```

**Latency analysis for 1000 friends across 4 shards:**
- ~250 ZSCORE per shard, pipelined = 1 round-trip per shard
- 4 shards queried in parallel = max(shard latencies) ≈ 2-3ms
- In-memory sort of 1000 items: < 0.1ms
- Total: ~5-8ms. Still within 50ms budget.

**For 5000+ friends:** Use the precomputed cache (Tier 2). The fan-out write path handles cross-shard complexity at write time, so reads are always a single local ZSET lookup.

### Problem 3: Latency Budget Breakdown (500ms requirement)

```
┌─────────────────────────────────────────────────────────────┐
│                    500ms Total Budget                         │
├──────────────┬──────────────┬──────────────┬────────────────┤
│  API Gateway │  Service     │  Redis       │  Player Name   │
│  + Auth      │  Logic       │  Queries     │  Enrichment    │
│  ~20ms       │  ~5ms        │  ~5-10ms     │  ~10-20ms      │
│              │              │  (pipelined) │  (cached)       │
├──────────────┴──────────────┴──────────────┴────────────────┤
│  Total: ~40-55ms                                             │
│  Headroom: ~445ms for retries, GC pauses, tail latency       │
└─────────────────────────────────────────────────────────────┘
```

Even the worst case (1000 friends, cross-shard, parallel pipelines) fits comfortably within 500ms. The 50ms target is achievable for p99.

---

## 17. Follow-Up Questions — Direct Answers

### Q1: How do you find 20 globally surrounding players when users are sharded by ID?

**Answer:** Maintain a separate global rank index — a single Redis ZSET that contains ALL players' scores, hosted on a dedicated high-memory Redis instance with read replicas.

- Every score update writes to both the user shard (for write throughput distribution) and the global rank index (for rank queries).
- "Around me" queries go directly to the global rank index: `ZREVRANK` to get rank, then `ZREVRANGE rank-10 rank+10` to get surrounding players.
- The global index update can be async (1-2s delay) since "around me" doesn't need millisecond freshness.
- This is a dual-write pattern: shards handle write throughput, global index handles rank queries.

```python
def get_around_me_sharded(player_id, game_id, period, range_size=10):
    # Always query the global rank index, not user shards
    global_key = f"lb:global:{game_id}:{period}"

    rank = redis_global.zrevrank(global_key, player_id)
    start = max(0, rank - range_size)
    stop = rank + range_size

    return redis_global.zrevrange(global_key, start, stop, withscores=True)
```

---

### Q2: What happens when a single Redis shard fails?

**Answer:** Three layers of protection:

**Layer 1 — Redis Sentinel / Cluster automatic failover:**
```
Primary (Shard 2) fails
        │
        ▼
Sentinel detects failure (1-2 seconds)
        │
        ▼
Promotes Replica → new Primary (automatic)
        │
        ▼
Clients reconnect via Sentinel (transparent)

Downtime: 1-5 seconds. Acceptable for a game leaderboard.
```

**Layer 2 — Read replica serves reads during failover:**
- Reads continue from replicas with stale data (seconds old at most)
- Writes queue in the service layer and retry after failover completes

**Layer 3 — Graceful degradation:**
```python
def get_player_rank_with_fallback(player_id, game_id, period):
    try:
        return redis.zrevrank(f"lb:{game_id}:{period}", player_id)
    except RedisConnectionError:
        # Fallback: return cached rank from local app cache (stale but available)
        cached = local_cache.get(f"rank:{player_id}:{game_id}:{period}")
        if cached:
            return {"rank": cached, "stale": True}
        # Last resort: return "rank unavailable" — don't crash the game
        return {"rank": None, "message": "Leaderboard temporarily unavailable"}
```

**For the 100K users on the failed shard:**
- Reads: served from replica (no impact)
- Writes: buffered in-memory or in Kafka, replayed after failover (1-5s delay)
- No data loss: Redis AOF persistence + replica replication ensures durability

---

### Q3: How does friends leaderboard perform with 1000+ friends across all shards?

**Answer:** Two strategies depending on read frequency:

**Infrequent reads (user opens friends board occasionally):**
- Parallel pipelined ZSCORE across all shards (see Section 16, Problem 2)
- 1000 friends ÷ 4 shards = 250 pipelined commands per shard
- 4 parallel round-trips: ~3ms each, max = ~3ms (parallel)
- Total with enrichment: ~10-15ms. Well within budget.

**Frequent reads (competitive game, users check constantly):**
- Use precomputed friends leaderboard (Tier 2, Section 15)
- Fan-out writes update each follower's cached ZSET on score change
- Read is a single `ZREVRANGE` on a local ZSET: ~1-2ms
- Cross-shard complexity is absorbed at write time, not read time

**The key insight:** Move the cross-shard join from the read path to the write path. Reads become local. Writes fan out asynchronously.

---

### Q4: Why use Kafka for 1-5K events/sec when Redis can handle this directly?

**Answer:** For the leaderboard hot path, you're right — Kafka is unnecessary overhead. Redis handles 1-5K ZADD/sec trivially. Here's when each makes sense:

**Write directly to Redis (no Kafka) for:**
- Global leaderboard updates (ZADD to ZSET) — latency-critical, Redis handles it natively
- Friends leaderboard fan-out — Redis pipelines are faster than Kafka round-trips

**Use Kafka only for the cold path:**
- Score history persistence (append to DynamoDB/Postgres) — not latency-critical
- Analytics pipeline (aggregate stats, anomaly detection) — batch processing
- Audit trail — compliance, not real-time

```
Revised Architecture:

                    HOT PATH (synchronous, low-latency)
Client → Service ──────────────────────────────────────→ Redis ZADD (leaderboard)
                │                                         Redis ZADD (friends fan-out)
                │
                │   COLD PATH (asynchronous, can be delayed)
                └──→ Kafka ──→ Score History DB
                            ──→ Analytics Pipeline
                            ──→ Anomaly Detection
```

**Why not eliminate Kafka entirely?**
- Score history writes to a relational DB can spike in latency (disk I/O, locks)
- Kafka decouples the hot path from slow consumers
- If the analytics pipeline is down, scores still process — Kafka buffers
- But for the leaderboard itself: Redis direct, no Kafka in the middle

---

### Q5: Race condition between score updates and leaderboard queries during Kafka delay?

**Answer:** There is no race condition on the hot path because we don't use Kafka for the leaderboard.

```
Timeline:
T=0   Player submits score 2500
T=1ms Redis ZADD updates leaderboard (synchronous, in the request path)
T=2ms Response returned to player with new rank
T=2ms Kafka event published (async, fire-and-forget)
T=50ms Kafka consumer writes to Score History DB

Any leaderboard query after T=1ms sees the updated score.
The Kafka delay only affects the cold path (history, analytics).
```

**The only scenario where staleness exists:**
- Read replicas of Redis lag behind primary by ~1-10ms (replication delay)
- A player submits a score, then immediately queries their rank from a replica
- They might see their old rank for ~10ms

**Mitigation: Read-your-writes consistency**

```python
def submit_and_get_rank(player_id, score, game_id):
    # Write to primary
    redis_primary.zadd(f"lb:{game_id}:alltime", {player_id: score}, gt=True)

    # Read rank from PRIMARY (not replica) for this specific request
    rank = redis_primary.zrevrank(f"lb:{game_id}:alltime", player_id)

    return {"rank": rank + 1}
    # Subsequent reads from other users go to replicas (eventually consistent, fine)
```

This gives the submitting player immediate consistency while everyone else reads from replicas. The staleness window for other players is ~10ms — imperceptible in a game.
