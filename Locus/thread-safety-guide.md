# Thread Safety Guide for Java Classes

## Core Principle

**Shared mutable state is the source of all concurrency bugs.** Eliminate any one of those three words (shared, mutable, state) and the problem goes away.

---

## 1. Make It Immutable (Best Option When Possible)

If an object never changes after construction, it's automatically thread-safe — no locks needed.

```java
public class Room {
    private final String id;
    private final String name;
    private final int capacity;
    // no setters, all fields assigned in constructor
}
```

**Rules:**
- All fields `final`
- No setters or mutating methods
- If fields reference collections, make them unmodifiable copies
- Don't leak `this` during construction

---

## 2. Stateless Classes

If a class has no fields at all, it's inherently safe. Strategy/utility classes often fit here.

```java
public class TimeBookingStrategy implements BookingStrategy {
    // no fields — operates purely on method arguments
}
```

---

## 3. Confine Mutable State to a Single Thread

If only one thread ever accesses an object, no synchronization is needed.

**Examples:**
- Thread-local variables (`ThreadLocal<T>`)
- Objects passed through a queue and only used by the consumer
- Objects created and used entirely within a single method

---

## 4. Use Thread-Safe Data Structures

Java's `java.util.concurrent` package offers lock-free or internally-synchronized structures:

| Need | Use |
|------|-----|
| Map | `ConcurrentHashMap` |
| List | `CopyOnWriteArrayList` (read-heavy) or `Collections.synchronizedList` |
| Queue | `ConcurrentLinkedQueue`, `BlockingQueue` |
| Counter | `AtomicInteger`, `AtomicLong` |
| Reference | `AtomicReference` |

These avoid explicit locking for individual operations, but you still need external synchronization for **compound operations** (check-then-act).

---

## 5. Synchronize Compound Operations with Locks

When you need atomicity across multiple steps (read → decide → write), use explicit locking:

```java
ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
lock.lock();
try {
    // check availability + insert — atomic together
} finally {
    lock.unlock();
}
```

**Guidelines:**
- Keep critical sections short
- Always unlock in `finally`
- Use fine-grained locks (per-room, per-entity) over a single global lock to reduce contention
- Avoid calling unknown/external code while holding a lock (risk of deadlock)
- Acquire multiple locks in a consistent order to prevent deadlocks

---

## 6. Don't Leak Mutable Internal State

Even if your class is properly synchronized internally, returning a live reference to an internal list lets callers bypass your locks.

```java
// Bad — caller gets a reference to the live list
return bookings.get(roomId);

// Good — return an immutable snapshot
return List.copyOf(bookings.getOrDefault(roomId, List.of()));
```

---

## 7. Document the Thread-Safety Contract

Make it explicit so users of your class know what's safe:

```java
/**
 * Thread-safe. All booking operations are serialized per room.
 * Returned lists are immutable snapshots.
 */
public class BookingManager { ... }
```

Use annotations like `@ThreadSafe`, `@NotThreadSafe`, `@GuardedBy("lock")` from `javax.annotation.concurrent` when available.

---

## Quick Decision Tree

```
Can you make it immutable?
  └─ Yes → done, inherently safe
  └─ No → Is the state confined to one thread?
              └─ Yes → done, no sync needed
              └─ No → Are operations single-step?
                          └─ Yes → use concurrent data structures
                          └─ No → use explicit locks for compound operations
```

---

## Common Pitfalls

1. **`getOrDefault` with `new Lock()`** — Creates a new lock each time; threads never share it. Use `computeIfAbsent` instead.
2. **Check-then-act without locking** — Checking availability then inserting outside a lock allows race conditions.
3. **Synchronizing on the wrong monitor** — `synchronized(localVariable)` is useless if each thread gets a different instance.
4. **Publishing partially-constructed objects** — Don't let `this` escape the constructor (e.g., passing `this` to another thread or registering a listener).
5. **Iterating over a collection while another thread modifies it** — Use a snapshot copy or a concurrent collection that supports safe iteration.
