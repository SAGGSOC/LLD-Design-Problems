# In-Memory Cache: System Design (Single-Node)

## 1. Problem Statement

Design a single-node, in-memory key-value cache (think Redis-lite or Memcached-lite) that supports:

- `GET(key)` → returns value or null
- `PUT(key, value, TTL?)` → inserts/updates a key with optional TTL
- `DELETE(key)` → removes a key
- Automatic eviction when memory is full (LRU)
- TTL-based expiration
- Thread-safe concurrent access
- Durability via Write-Ahead Log (WAL)

---

## 2. High-Level Design (HLD)

### 2.1 Core Components

```
┌─────────────────────────────────────────────────────┐
│                   Client Requests                    │
│              GET / PUT / DELETE                       │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────┐
│                  API Layer                            │
│         (validates input, routes commands)            │
└──────────────────────┬──────────────────────────────┘
                       │
          ┌────────────┼────────────────┐
          ▼            ▼                ▼
   ┌────────────┐ ┌──────────┐  ┌─────────────┐
   │  Cache      │ │  TTL     │  │  WAL        │
   │  Engine     │ │  Manager │  │  (Write     │
   │  (HashMap + │ │  (Expiry │  │   Ahead     │
   │   DLL)      │ │   Heap)  │  │   Log)      │
   └─────┬──────┘ └────┬─────┘  └──────┬──────┘
         │              │               │
         ▼              ▼               ▼
   ┌────────────┐ ┌──────────┐  ┌─────────────┐
   │  Memory    │ │  Lazy +  │  │  Disk       │
   │  Store     │ │  Active  │  │  Append     │
   │            │ │  Cleanup │  │  File       │
   └────────────┘ └──────────┘  └─────────────┘
```

### 2.2 Data Flow

**PUT(key, value, TTL=60s):**
```
1. Append to WAL on disk (durability)
2. Insert/update in HashMap
3. Move node to head of LRU doubly-linked list
4. Register TTL expiry in min-heap (expiry_time = now + 60s)
5. If capacity exceeded → evict LRU tail node
6. Return OK
```

**GET(key):**
```
1. Lookup key in HashMap → O(1)
2. If found, check if TTL expired (lazy expiration)
   - If expired → delete it, return NULL
   - If valid   → move node to head of LRU list, return value
3. If not found → return NULL
```

**DELETE(key):**
```
1. Append DELETE to WAL
2. Remove from HashMap
3. Unlink node from LRU doubly-linked list
4. Mark expired in TTL heap (lazy removal)
5. Return OK
```

### 2.3 Why These Choices Matter (Interview Talking Points)

| Decision | Why |
|---|---|
| HashMap + Doubly Linked List | O(1) get, put, delete AND O(1) LRU eviction |
| Min-Heap for TTL | O(log n) insert, O(1) peek at next expiry |
| Lazy + Active TTL cleanup | Lazy avoids scanning on every read; active thread prevents memory bloat |
| WAL before mutation | Crash recovery without full snapshots |
| Single-node focus | No CAP theorem. No partition tolerance needed. Focus on locking, memory, and data structures. |

### 2.4 Eviction Strategy: LRU

```
Most Recently Used                          Least Recently Used
     HEAD ←→ [A] ←→ [B] ←→ [C] ←→ [D] ←→ TAIL
                                             ↑
                                        evict this on capacity overflow
```

- Every GET/PUT moves the accessed node to HEAD
- When capacity is full, evict from TAIL
- All operations O(1) with HashMap pointing directly to DLL nodes

### 2.5 TTL Expiration: Dual Strategy

**Lazy expiration:** On every GET, check if the key's TTL has passed. If yes, delete it and return null. Cheap, but stale keys linger if never accessed.

**Active expiration:** A background thread periodically pops expired entries from the min-heap and removes them. Prevents memory from filling with dead keys.

```
Min-Heap (ordered by expiry_time):
   ┌───────────┐
   │ key=C     │  expires at T+10  ← next to expire
   │ key=A     │  expires at T+30
   │ key=B     │  expires at T+60
   └───────────┘
```

### 2.6 Write-Ahead Log (WAL)

```
[timestamp] PUT key1 value1 TTL=60
[timestamp] PUT key2 value2 TTL=0
[timestamp] DELETE key1
```

- Append-only file on disk
- Written BEFORE the in-memory mutation
- On crash recovery: replay the WAL to rebuild state
- Periodic compaction: rewrite WAL with only live keys to prevent unbounded growth

### 2.7 Concurrency Model

Since this is single-node, no distributed consensus needed. The focus is read/write contention on shared memory.

**Option A — Read-Write Lock (good default):**
- Multiple readers can hold the lock simultaneously
- Writers get exclusive access
- Good when reads >> writes

**Option B — Striped/Segmented Locking (high throughput):**
- Partition keyspace into N segments (e.g., 16 or 64)
- Each segment has its own lock
- `segment = hash(key) % N`
- Dramatically reduces contention; writers on segment 3 don't block readers on segment 7

**Option C — Lock-Free (advanced, mention but don't over-engineer):**
- CAS-based concurrent HashMap (like Java's ConcurrentHashMap)
- Mention as an option, but segmented locking is usually sufficient

**Recommendation for interview:** Start with RW-Lock, then upgrade to striped locking when the interviewer pushes on throughput.

---

## 3. Low-Level Design (LLD)

### 3.1 Class Diagram

```
┌──────────────────────────────────┐
│           Cache                   │
│──────────────────────────────────│
│ - segments: CacheSegment[]        │
│ - ttlManager: TTLManager          │
│ - wal: WriteAheadLog              │
│ - maxCapacity: int                │
│──────────────────────────────────│
│ + get(key): Value?                │
│ + put(key, value, ttl?): void     │
│ + delete(key): bool               │
│ + shutdown(): void                │
└──────────┬───────────────────────┘
           │ owns
           ▼
┌──────────────────────────────────┐
│        CacheSegment               │
│──────────────────────────────────│
│ - map: HashMap<Key, DLLNode>      │
│ - lruList: DoublyLinkedList       │
│ - lock: ReadWriteLock             │
│ - currentSize: int                │
│ - segmentCapacity: int            │
│──────────────────────────────────│
│ + get(key): Value?                │
│ + put(key, value, expiry): void   │
│ + delete(key): bool               │
│ + evictLRU(): void                │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│         DLLNode                   │
│──────────────────────────────────│
│ - key: String                     │
│ - value: byte[]                   │
│ - expiryTimestamp: long           │
│ - prev: DLLNode                   │
│ - next: DLLNode                   │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│        TTLManager                 │
│──────────────────────────────────│
│ - heap: MinHeap<TT
LEntry>       │
│ - cleanupThread: Thread           │
│──────────────────────────────────│
│ + register(key, expiryTime): void │
│ + cleanupExpired(cache): void     │
│ + start(): void                   │
│ + stop(): void                    │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│       WriteAheadLog               │
│──────────────────────────────────│
│ - file: FileOutputStream          │
│ - lock: Mutex                     │
│──────────────────────────────────│
│ + append(op, key, value?): void   │
│ + replay(cache): void             │
│ + compact(liveKeys): void         │
└──────────────────────────────────┘
```

### 3.2 Core Data Structures

**HashMap + Doubly Linked List (per segment):**

```
HashMap:
  "user:1" → DLLNode(key="user:1", value=..., expiry=T+60)
  "user:2" → DLLNode(key="user:2", value=..., expiry=T+120)
  "sess:A" → DLLNode(key="sess:A", value=..., expiry=0)  // no TTL

DLL (LRU order):
  HEAD ←→ [user:1] ←→ [sess:A] ←→ [user:2] ←→ TAIL
  (most recent)                      (least recent → evict candidate)
```

**Min-Heap for TTL:**

```
TTLEntry { key: String, expiryTimestamp: long }

Heap[0] = { "user:1", T+60  }   ← soonest to expire
Heap[1] = { "user:2", T+120 }
```

### 3.3 Pseudocode: Key Operations

#### PUT

```python
def put(key, value, ttl_seconds=0):
    segment = segments[hash(key) % NUM_SEGMENTS]

    # 1. WAL first (durability)
    wal.append("PUT", key, value, ttl_seconds)

    # 2. Acquire write lock on segment
    segment.lock.write_lock():

        expiry = current_time() + ttl_seconds if ttl_seconds > 0 else 0

        if key in segment.map:
            # Update existing
            node = segment.map[key]
            node.value = value
            node.expiryTimestamp = expiry
            segment.lruList.move_to_head(node)
        else:
            # Insert new
            if segment.currentSize >= segment.segmentCapacity:
                segment.evict_lru()  # remove tail

            node = DLLNode(key, value, expiry)
            segment.map[key] = node
            segment.lruList.add_to_head(node)
            segment.currentSize += 1

    # 3. Register TTL (outside segment lock — TTLManager has its own lock)
    if ttl_seconds > 0:
        ttlManager.register(key, expiry)
```

#### GET

```python
def get(key):
    segment = segments[hash(key) % NUM_SEGMENTS]

    segment.lock.read_lock():
        if key not in segment.map:
            return None

        node = segment.map[key]

        # Lazy TTL check
        if node.expiryTimestamp > 0 and current_time() > node.expiryTimestamp:
            # Upgrade to write lock to delete
            segment.lock.upgrade_to_write():
                segment.lruList.remove(node)
                del segment.map[key]
                segment.currentSize -= 1
            return None

        segment.lruList.move_to_head(node)
        return node.value
```

#### EVICT LRU

```python
def evict_lru(segment):
    # Called while holding write lock on segment
    tail = segment.lruList.tail.prev  # node before sentinel
    if tail == segment.lruList.head:
        return  # empty list

    del segment.map[tail.key]
    segment.lruList.remove(tail)
    segment.currentSize -= 1
    wal.append("DELETE", tail.key)
```

#### TTL CLEANUP (Background Thread)

```python
def ttl_cleanup_loop():
    while running:
        sleep(1 second)  # configurable interval

        while heap is not empty and heap.peek().expiryTimestamp <= current_time():
            entry = heap.pop()
            segment = segments[hash(entry.key) % NUM_SEGMENTS]

            segment.lock.write_lock():
                if entry.key in segment.map:
                    node = segment.map[entry.key]
                    # Double-check: key might have been re-inserted with new TTL
                    if node.expiryTimestamp <= current_time():
                        segment.lruList.remove(node)
                        del segment.map[entry.key]
                        segment.currentSize -= 1
```

#### WAL REPLAY (Crash Recovery)

```python
def recover_from_wal():
    for entry in wal.read_all_entries():
        if entry.op == "PUT":
            put(entry.key, entry.value, entry.ttl)  # re-inserts
        elif entry.op == "DELETE":
            delete(entry.key)
    # After replay, compact WAL
    wal.compact(get_all_live_keys())
```

### 3.4 Segmented Locking Detail

```
Total capacity = 10,000 keys
NUM_SEGMENTS = 16
Per-segment capacity = 625

Key "user:42" → hash = 7823 → segment = 7823 % 16 = 15

Segment 0:  [RWLock] [HashMap] [DLL]
Segment 1:  [RWLock] [HashMap] [DLL]
...
Segment 15: [RWLock] [HashMap] [DLL]  ← "user:42" lives here
```

Benefit: A write to segment 15 doesn't block reads on segments 0–14. Under uniform key distribution, contention drops by ~16x.

### 3.5 Memory Estimation

For 1 million keys, average key = 32 bytes, average value = 256 bytes:

| Component | Per Entry | Total (1M keys) |
|---|---|---|
| Key (String) | ~32 B | ~32 MB |
| Value (byte[]) | ~256 B | ~256 MB |
| DLLNode overhead (pointers, expiry) | ~48 B | ~48 MB |
| HashMap entry overhead | ~32 B | ~32 MB |
| TTL heap entry | ~24 B | ~24 MB |
| **Total** | **~392 B** | **~392 MB** |

---

## 4. Interview Walkthrough: How to Present This

### Step 1: Clarify Requirements (2 min)
- "Is this single-node or distributed?" → Single-node. **No CAP theorem.**
- "What operations?" → GET, PUT, DELETE
- "Eviction policy?" → LRU
- "TTL support?" → Yes
- "Durability needed?" → WAL for crash recovery
- "Concurrency?" → Multi-threaded access

### Step 2: HLD (10 min)
- Draw the component diagram (API → Cache Engine → TTL Manager → WAL)
- Explain HashMap + DLL for O(1) LRU
- Explain min-heap for TTL
- Explain WAL for durability
- Discuss concurrency: RW-Lock → Segmented Locking

### Step 3: LLD (15 min)
- Class diagram with Cache, CacheSegment, DLLNode, TTLManager, WAL
- Walk through PUT and GET pseudocode
- Explain eviction and TTL cleanup
- Discuss lock granularity and segment sizing

### Step 4: Trade-offs & Extensions (5 min)
- "What if we need LFU instead of LRU?" → Replace DLL with frequency-count buckets
- "What about memory limits in bytes, not key count?" → Track byte size per entry, evict until under threshold
- "Snapshot persistence?" → Periodic RDB-style dump alongside WAL
- "Distributed?" → NOW you bring in consistent hashing, replication, CAP theorem

---

## 5. Common Mistakes to Avoid

| Mistake | Why It's Wrong |
|---|---|
| Bringing up CAP theorem for single-node | CAP is about distributed systems with network partitions. Single-node has no partitions. |
| Using a sorted set for LRU | O(log n) vs O(1) with HashMap+DLL. Interviewer will push back. |
| Forgetting lazy TTL check on GET | Stale data returned to client. Always check expiry on read. |
| Single global lock | Kills throughput. Segmented locking is the standard answer. |
| No WAL compaction | WAL grows unbounded. Mention periodic compaction. |
| Over-engineering with Raft/Paxos | Single-node. No consensus needed. Stay focused. |

---

## 6. Key Complexity Summary

| Operation | Time | Space |
|---|---|---|
| GET | O(1) | — |
| PUT | O(1) amortized | O(1) per entry |
| DELETE | O(1) | — |
| LRU Eviction | O(1) | — |
| TTL Register | O(log n) | O(n) heap |
| TTL Cleanup (per expired key) | O(log n) | — |
| WAL Append | O(1) disk I/O | O(n) on disk |
| WAL Replay | O(n) | — |
