# Quick Commerce (Zepto/Blinkit) — System Design Document

---

## 1. Functional Requirements

### Core Operations

1. `searchItems(query, location, filters?) → items[]` — Customer searches for grocery items available at their nearest dark store.
2. `addToCart(userId, itemId, quantity) → cart` — Customer adds items to cart with real-time inventory validation.
3. `placeOrder(userId, cartId, addressId, paymentMethod) → orderId` — Customer places order; triggers inventory lock, payment capture, and rider assignment.
4. `trackOrder(orderId) → {status, riderLocation, eta}` — Customer tracks order in real-time from packing to doorstep.
5. `assignRider(orderId, darkStoreId) → riderId` — System auto-assigns nearest available rider to a packed order.
6. `updateInventory(darkStoreId, itemId, delta) → confirmation` — Warehouse staff updates stock levels after restocking or damage.

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| Delivery promise? | 10 minutes from order placement. |
| How many dark stores per city? | ~30 dark stores in a metro city, each covering 2-3 km radius. |
| SKU count per dark store? | ~2,000 SKUs (curated high-demand grocery items). |
| Multiple items per order? | Yes, average 5-8 items per order. |
| Surge pricing? | Out of scope for initial design. |
| Return/refund flow? | Out of scope; assume customer support handles manually. |
| Rider is employee or gig? | Gig riders with shift-based availability. |

### Assumptions

- 10M registered users in a metro city, 500K DAU.
- Peak hours: 9am-11am (morning), 6pm-9pm (evening).
- Average order value: $12. Average 5 items per order.
- 80% of orders come from 20% of dark stores (power law).
- Rider completes ~3 deliveries per hour during peak.
- Each dark store has ~15 active riders during peak.


---

## 2. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Scale | 10M registered users, 500K DAU, 30 dark stores/city, ~2K SKUs/store |
| Throughput | 5,000 orders/min peak city-wide → ~83 orders/sec; 50K search queries/sec |
| Latency | Search p50 < 30ms, p99 < 100ms; Order placement p50 < 200ms, p99 < 500ms; Tracking p50 < 50ms |
| Read/write ratio | 50:1 (search/browse heavy, orders are writes) |
| Consistency | Strong for inventory (prevent overselling); eventual OK for search, tracking location |
| Availability | 99.95% — downtime = lost revenue and spoiled perishables |
| Durability | Zero order/payment data loss |
| Special constraints | Inventory decrement must be atomic; rider assignment must be idempotent; order placement must be exactly-once |

### Back-of-Envelope Math

```
500K DAU × 2 orders/day avg = 1M orders/day
Peak = 4× average → 4M orders equivalent in peak hours (5 hours)
= 4M / (5 × 3600) ≈ 222 orders/sec peak

Search: each user browses ~10 searches/session, 2 sessions/day
= 500K × 10 × 2 / 86400 ≈ 115K searches/day-avg
Peak (10×) ≈ 1.15M/hour ≈ 320 searches/sec

Inventory updates: 222 orders/sec × 5 items = 1,110 inventory decrements/sec peak

Rider location updates: 30 stores × 15 riders × 1 update/3sec = 150 location writes/sec
```


---

## 3. Core Entities

```
User:
  user_id (PK) | name   | phone       | default_address_id | created_at
  U-90210       | [name] | [phone]     | ADDR-501           | 2026-01-15
```

```
Address:
  address_id (PK) | user_id | lat       | lng       | full_address    | label
  ADDR-501          | U-90210 | 19.0760   | 72.8777   | [address]       | home
```

```
DarkStore:
  store_id (PK) | name          | lat      | lng      | city    | radius_km | status
  DS-MUM-07      | Andheri West  | 19.1364  | 72.8296  | Mumbai  | 3.0       | active
```

```
Item (Catalog):
  item_id (PK) | name            | category   | base_price | image_url | weight_g
  ITM-4421      | Amul Toned Milk | dairy      | 1.20       | /img/4421 | 500
```

```
StoreInventory:
  store_id (PK) | item_id (SK) | quantity | reserved | last_restocked
  DS-MUM-07      | ITM-4421     | 48       | 3        | 2026-03-13T06:00
```

```
Order:
  order_id (PK) | user_id | store_id  | rider_id | status     | total  | placed_at           | delivered_at
  ORD-88123      | U-90210 | DS-MUM-07 | R-1042   | delivering | 14.50  | 2026-03-13T19:02:00 | null
```

```
OrderItem:
  order_id (PK) | item_seq (SK) | item_id  | quantity | price_at_order
  ORD-88123      | 1             | ITM-4421 | 2        | 1.20
```

```
Rider:
  rider_id (PK) | name   | phone   | current_store_id | status      | lat      | lng
  R-1042         | [name] | [phone] | DS-MUM-07        | on_delivery | 19.0780  | 72.8790
```

```
Cart (Redis):
  key: cart:{user_id}
  value: { store_id, items: [{ item_id, quantity, price }], updated_at }
  TTL: 24 hours
```

### Relationships

- User 1 → N Addresses, 1 → N Orders
- DarkStore 1 → N StoreInventory, 1 → N Riders (shift-based)
- Order 1 → N OrderItems, N → 1 Rider
- StoreInventory links DarkStore ↔ Item (many-to-many with quantity)
- Cart is ephemeral (Redis), linked to User and DarkStore


---

## 4. API Routes

```
GET /items/search?q={query}&lat={lat}&lng={lng}&category={cat}
  Response: { storeId: string, items: [{ itemId, name, price, inStock, imageUrl }] }
  Errors: 400 (missing location), 404 (no dark store in range)
  Note: storeId resolved from (lat, lng) via geo-lookup
```

```
POST /cart/items
  Body: { itemId: string, quantity: int }
  Response: { cart: { storeId, items[], subtotal } }
  Errors: 400 (quantity < 1), 409 (item out of stock), 404 (item not found)
  Auth: User JWT required
```

```
POST /orders
  Body: { cartId: string, addressId: string, paymentMethod: string }
  Response: { orderId: string, status: "placed", estimatedDelivery: string }
  Errors: 400 (empty cart), 402 (payment failed), 409 (inventory changed since cart)
  Auth: User JWT required
  Note: Idempotency key in header to prevent double-orders
```

```
GET /orders/{orderId}/track
  Response: { status: string, riderName?: string, riderLat?: float, riderLng?: float, eta?: int }
  Errors: 404 (order not found), 403 (not your order)
  Auth: User JWT required
  Note: Also available via WebSocket at ws://host/orders/{orderId}/live
```

```
POST /internal/riders/assign
  Body: { orderId: string, storeId: string }
  Response: { riderId: string, estimatedPickupTime: int }
  Errors: 503 (no riders available)
  Auth: Internal service-to-service token
```

```
PATCH /internal/inventory/{storeId}/{itemId}
  Body: { delta: int, reason: "restock" | "damage" | "order_cancel" }
  Response: { newQuantity: int }
  Errors: 409 (would go negative), 404 (store/item not found)
  Auth: Warehouse staff or internal service token
```


---

## 5. High-Level Design

### Architecture Diagram

```
+----------------+     +----------------+
|  Customer App  |     |  Rider App     |
|  (mobile)      |     |  (mobile)      |
+-------+--------+     +-------+--------+
        |                       |
        +-----------+-----------+
                    |
            +-------+--------+
            |   API Gateway  |
            | (auth, rate    |
            |  limit, route) |
            +-------+--------+
                    |
    +---------------+----------------+
    |               |                |
+---+----+   +-----+------+   +-----+------+
| Search |   |   Order    |   |  Tracking  |
| Service|   |  Service   |   |  Service   |
+---+----+   +-----+------+   +-----+------+
    |               |                |
    |         +-----+------+        |
    |         | Inventory  |        |
    |         | Service    |        |
    |         +-----+------+        |
    |               |                |
+---+----+   +-----+------+   +-----+------+
| Elastic|   | PostgreSQL |   |   Redis    |
| Search |   | (orders,   |   | (cart,     |
| (items)|   |  inventory)|   |  rider loc,|
+--------+   +------------+   |  sessions) |
                               +------------+
                    |
            +-------+--------+
            | Rider Assign.  |
            | Service        |
            +-------+--------+
                    |
            +-------+--------+
            | Notification   |
            | Service (push, |
            |  SMS)          |
            +----------------+
```

### Component Responsibility

| Component | Role |
|---|---|
| Customer App | Browse, search, cart, order, track delivery |
| Rider App | Accept orders, navigate to store/customer, update location |
| API Gateway | JWT validation, rate limiting (1000 req/min/user), routing |
| Search Service | Full-text + filtered search over store-local catalog |
| Order Service | Order lifecycle: validate → lock inventory → charge → assign rider |
| Inventory Service | Stock management, atomic decrement/increment, restock alerts |
| Tracking Service | Real-time order status + rider GPS via WebSocket |
| Rider Assignment Service | Nearest-available rider matching using geo-index |
| Notification Service | Push notifications, SMS for order updates |
| ElasticSearch | Inverted index for item search, filtered by store_id |
| PostgreSQL | Source of truth for orders, inventory, users, stores |
| Redis | Cart storage, rider location (geo), session cache, pub/sub for tracking |

### Data Flow: Place an Order (the critical path)

1. Customer taps "Place Order" → `POST /orders` with idempotency key.
2. API Gateway validates JWT, checks rate limit → forwards to Order Service.
3. Order Service reads cart from Redis → validates items still in stock.
4. Order Service begins DB transaction:
   a. Decrements `StoreInventory.quantity` for each item (`SET quantity = quantity - :qty WHERE quantity >= :qty`).
   b. Inserts Order + OrderItems rows.
   c. If any decrement fails → rollback, return 409.
5. Order Service calls Payment Service (external) → captures payment.
   - If payment fails → rollback inventory, return 402.
6. Order Service publishes `ORDER_PLACED` event to message queue.
7. Rider Assignment Service consumes event → queries Redis geo-index for nearest available rider → assigns rider, updates order.
8. Notification Service consumes event → sends push to customer ("Order confirmed") and rider ("New order at Store X").
9. Cart cleared from Redis.
10. Response returned: `{ orderId, status: "placed", estimatedDelivery: "10 min" }`.

### Technology Justification

| Choice | Why | Alternatives Considered |
|---|---|---|
| PostgreSQL | ACID for inventory/orders, prevents overselling with row-level locks | DynamoDB (no multi-row transactions), MongoDB (weaker consistency) |
| Redis | Sub-ms cart reads, native geo commands (GEOADD/GEORADIUS) for rider location | Memcached (no geo, no persistence), PostgreSQL PostGIS (higher latency for hot path) |
| ElasticSearch | Full-text search with filters, relevance scoring, ~2K docs/store is tiny | PostgreSQL FTS (slower at scale), Algolia (vendor lock-in, cost) |
| Kafka/SQS | Decouple order placement from rider assignment and notifications | Direct HTTP calls (tight coupling, failure cascading) |
| WebSocket | Real-time tracking without polling; rider sends location every 3s | SSE (one-direction), polling (wastes bandwidth at 150 riders × 1 update/3s) |


---

## 6. Deep Dives

### Deep Dive 1: Inventory — Preventing Overselling Under Concurrency

**Problem**: During flash sales or peak hours, 50+ customers may try to buy the last 5 units of a popular item simultaneously. Naive decrement leads to overselling (negative stock).

**Approach**: Atomic conditional decrement in PostgreSQL.

```python
def lock_inventory(store_id, items):
    """Atomically reserve inventory for all items in an order.
    Returns True if all items reserved, False if any item insufficient."""
    with db.transaction(isolation="READ_COMMITTED"):
        for item in items:
            result = db.execute("""
                UPDATE store_inventory
                SET quantity = quantity - :qty,
                    reserved = reserved + :qty
                WHERE store_id = :store_id
                  AND item_id = :item_id
                  AND quantity >= :qty
            """, qty=item.quantity, store_id=store_id, item_id=item.item_id)

            if result.rows_affected == 0:
                raise InsufficientStock(item.item_id)
                # Transaction rolls back automatically

    return True
```

**Why this works**:
- `WHERE quantity >= :qty` is the guard — if stock is insufficient, zero rows update, no oversell.
- Row-level lock held only for the transaction duration (~5ms).
- `READ_COMMITTED` is sufficient because each UPDATE sees the latest committed value.

**Concurrency scenario**:

```
Stock of Milk = 5

Thread A: UPDATE ... SET quantity = 5 - 3 WHERE quantity >= 3  → succeeds, stock = 2
Thread B: UPDATE ... SET quantity = 2 - 3 WHERE quantity >= 3  → 0 rows affected → rollback
Thread C: UPDATE ... SET quantity = 2 - 1 WHERE quantity >= 1  → succeeds, stock = 1
```

**Reserved vs Available**: `reserved` field tracks items locked for orders being packed but not yet delivered. If order is cancelled, `reserved` is decremented and `quantity` restored.

**Latency**: Single UPDATE with composite index on `(store_id, item_id)` → < 3ms p99 per item. 5-item order = ~15ms total.

**Trade-off**: Row-level locking creates contention on hot items. At 222 orders/sec with 5 items each, worst case is ~1,110 inventory writes/sec spread across 30 stores × 2,000 SKUs = 60K rows. Hot items might see 10-20 concurrent locks — PostgreSQL handles this fine. If a single SKU gets 100+ concurrent writes, we'd shard inventory by store_id.

> Interview One-Liner: "Atomic conditional UPDATE with WHERE quantity >= N — zero rows affected means out of stock, no oversell possible."

---

### Deep Dive 2: Dark Store Selection — Geo-Based Routing

**Problem**: Customer opens the app → system must instantly determine which dark store serves their location. Wrong store = wrong catalog, wrong availability, delivery outside radius.

**Approach**: Two-tier lookup — Redis geo-index for speed, PostGIS for accuracy.

```
Redis GEOADD dark_stores 72.8296 19.1364 "DS-MUM-07"
Redis GEOADD dark_stores 72.8456 19.1180 "DS-MUM-12"
...
```

**Lookup flow**:

```python
def resolve_dark_store(user_lat, user_lng):
    # Tier 1: Redis GEORADIUS — O(N+log(N)) where N = stores in radius
    candidates = redis.georadius(
        "dark_stores", user_lng, user_lat,
        radius=5, unit="km", sort="ASC", count=3
    )
    # Returns: [("DS-MUM-07", 1.2km), ("DS-MUM-12", 2.8km)]

    if not candidates:
        raise NoStoreInRange()

    # Pick nearest active store
    for store_id, distance in candidates:
        store = cache.get(f"store:{store_id}")
        if store.status == "active" and distance <= store.radius_km:
            return store_id

    raise NoStoreInRange()
```

**Why Redis geo over PostGIS for hot path**:
- Redis GEORADIUS: ~0.5ms for 30 stores in a city.
- PostGIS ST_DWithin: ~5ms (still fast, but 10× slower and hits DB).
- PostGIS used as fallback and for batch analytics.

**Edge case — user on boundary of two stores**:

```
+--------+    +--------+
| DS-07  | U  | DS-12  |
| 1.2km  |<-->| 1.4km  |
+--------+    +--------+
```

Nearest store wins. If DS-07 is at capacity (queue > threshold), fall back to DS-12. This is a simple load-aware routing extension.

**Cache invalidation**: Store status changes (closed for restocking, emergency shutdown) publish to Redis pub/sub → all API servers update local cache within 1 second.

**Latency**: < 2ms p99 for store resolution.

**Trade-off**: Storing only 30 stores per city in Redis is trivial memory (~1KB). Scales to 1,000 stores with no architecture change. Beyond that, consider geohash-based sharding.

> Interview One-Liner: "Redis GEORADIUS for sub-ms dark store resolution — 30 stores fit in 1KB, PostGIS as fallback."

---

### Deep Dive 3: Rider Assignment — Nearest Available Matching

**Problem**: When an order is packed, assign the nearest available rider within 60 seconds. Bad assignment = late delivery = broken 10-minute promise.

**Approach**: Redis geo-index of available riders per store, scored by distance.

```
Redis GEOADD riders:DS-MUM-07 72.8300 19.1370 "R-1042"
Redis GEOADD riders:DS-MUM-07 72.8310 19.1350 "R-1088"
```

**Assignment flow**:

```python
def assign_rider(order_id, store_id):
    store = db.get(store_id)

    # Find nearest available riders within 2km of store
    candidates = redis.georadius(
        f"riders:{store_id}",
        store.lng, store.lat,
        radius=2, unit="km", sort="ASC", count=5
    )

    for rider_id, distance in candidates:
        # Atomic claim: SET rider status to "assigned" only if currently "available"
        claimed = redis.eval("""
            local status = redis.call('HGET', 'rider:' .. KEYS[1], 'status')
            if status == 'available' then
                redis.call('HSET', 'rider:' .. KEYS[1], 'status', 'assigned')
                redis.call('HSET', 'rider:' .. KEYS[1], 'order_id', ARGV[1])
                return 1
            end
            return 0
        """, keys=[rider_id], args=[order_id])

        if claimed:
            db.update("orders", order_id, {"rider_id": rider_id, "status": "rider_assigned"})
            notify_rider(rider_id, order_id)
            return rider_id

    # No rider available — retry with exponential backoff (max 3 retries)
    enqueue_retry(order_id, store_id, attempt=1)
    return None
```

**Why Lua script for claiming**: Without atomic check-and-set, two orders could claim the same rider. The Lua script runs atomically in Redis — no race condition.

**Rider location updates**: Rider app sends GPS every 3 seconds → `GEOADD riders:{store_id}`. If no update for 30 seconds, rider marked `offline` (heartbeat timeout).

**Retry strategy**: If no rider available, retry at 5s, 15s, 30s. After 3 failures, escalate to ops dashboard and notify customer of delay.

**Latency**: GEORADIUS on ~15 riders + Lua claim = < 3ms. End-to-end assignment (including DB update + push notification) < 200ms.

**Trade-off**: Per-store rider geo-index means a rider can only serve one store at a time. Simpler than a global rider pool but less optimal at store boundaries. For v2, consider a city-wide rider pool with store-proximity scoring.

> Interview One-Liner: "Redis GEORADIUS + Lua atomic claim — nearest rider assigned in < 3ms, no double-assignment."

---

### Deep Dive 4: Real-Time Order Tracking

**Problem**: Customer expects live rider location and ETA updates from order placement to delivery. Polling is wasteful at scale.

**Approach**: WebSocket per active order, backed by Redis pub/sub.

```
Architecture:

Rider App                    Tracking Service              Customer App
   |                              |                            |
   |-- POST /rider/location ----->|                            |
   |   {lat, lng, orderId}        |                            |
   |                              |-- Redis PUBLISH ---------->|
   |                              |   channel: order:{orderId} |
   |                              |                            |
   |                              |<-- WebSocket subscribe ----|
   |                              |    order:{orderId}         |
   |                              |                            |
   |                              |-- WS push: {lat,lng,eta} ->|
```

**Rider location ingestion**:

```python
def update_rider_location(rider_id, lat, lng, order_id):
    # Update rider's geo position
    redis.geoadd(f"riders:{store_id}", lng, lat, rider_id)

    # Update tracking channel
    eta = calculate_eta(lat, lng, order.delivery_address)
    redis.publish(f"order:{order_id}", json.dumps({
        "riderLat": lat, "riderLng": lng,
        "eta": eta, "status": "delivering"
    }))
```

**ETA calculation**: Haversine distance ÷ average speed (15 km/h for bike in city traffic). Updated every 3 seconds with rider's actual position.

```python
def calculate_eta(rider_lat, rider_lng, dest_address):
    distance_km = haversine(rider_lat, rider_lng, dest_address.lat, dest_address.lng)
    avg_speed_kmh = 15  # city bike speed
    eta_minutes = (distance_km / avg_speed_kmh) * 60
    return max(1, round(eta_minutes))  # minimum 1 minute
```

**Scale math**:
- Peak: 222 orders/sec, average delivery time 8 min → ~222 × 8 × 60/60 ≈ 1,776 concurrent active orders.
- Each order = 1 WebSocket + 1 Redis pub/sub channel.
- Rider updates every 3s → 1,776 / 3 ≈ 592 location publishes/sec.
- Redis pub/sub handles 100K+ messages/sec — well within capacity.

**Disconnection handling**: If customer's WebSocket drops, reconnect fetches latest state from Redis (`GET order:{orderId}:latest`). No missed updates — latest state always available.

**Latency**: Rider GPS → Redis publish → WebSocket push to customer: < 100ms end-to-end.

**Trade-off**: Redis pub/sub is fire-and-forget (no persistence). If Tracking Service restarts, active subscriptions are lost. Mitigation: customers auto-reconnect, and latest state is always in Redis hash. For guaranteed delivery, could use Kafka, but adds 50ms latency — not worth it for ephemeral location data.

> Interview One-Liner: "Redis pub/sub + WebSocket — 1,776 concurrent tracking channels at < 100ms latency, fire-and-forget is fine for ephemeral GPS data."


---

## 7. Closing Sections

### Complexity Summary

| Operation | Expected Latency | Big-O |
|---|---|---|
| Search items | < 30ms p99 | O(log N) ES query on ~2K docs |
| Add to cart | < 5ms p99 | O(1) Redis HSET |
| Resolve dark store | < 2ms p99 | O(N+log N) GEORADIUS, N=30 stores |
| Place order | < 200ms p99 | O(K) inventory locks, K=items in order |
| Assign rider | < 3ms p99 | O(N+log N) GEORADIUS + O(1) Lua claim, N=15 riders |
| Track order | < 100ms e2e | O(1) pub/sub publish + WebSocket push |
| Update inventory | < 3ms p99 | O(1) indexed UPDATE |

---

### Interview Walkthrough Pacing

| Section | Time | Notes |
|---|---|---|
| Requirements + clarifications | 3 min | Scope to single city, dine-in only analogy for dark store |
| Non-functional + math | 3 min | Show 222 orders/sec derivation, read/write ratio |
| Core entities + API | 5 min | Focus on Order, StoreInventory, Rider |
| High-level design | 8 min | Draw diagram, walk through order placement flow |
| Deep dive: Inventory oversell | 5 min | Atomic conditional UPDATE, concurrency scenario |
| Deep dive: Dark store geo-routing | 4 min | Redis GEORADIUS, boundary handling |
| Deep dive: Rider assignment | 5 min | Lua atomic claim, retry strategy |
| Deep dive: Real-time tracking | 4 min | Pub/sub + WebSocket, scale math |
| Trade-offs + extensions | 3 min | What changes at 100 cities |

---

### Follow-Up Questions

| Question | Answer |
|---|---|
| How do you handle a dark store going offline mid-peak? | Mark store inactive in Redis (< 1s propagation). In-flight orders: notify customers of delay, reassign to nearest store if items available. New orders routed to next-nearest store. |
| What if a rider's phone dies mid-delivery? | No heartbeat for 30s → mark rider offline. Reassign order to next available rider. Customer notified of delay. Original rider's last known location stored in Redis. |
| How do you handle flash sales (100× spike on one item)? | Rate limit cart additions per user (5/min). Inventory lock is the bottleneck — PostgreSQL row lock on hot SKU handles ~500 concurrent writes. Beyond that, shard inventory by item_id hash. |
| How would you expand to 100 cities? | Shard PostgreSQL by city (each city = separate DB). Redis cluster per city. ElasticSearch index per city. Shared user service across cities. |
| How do you ensure exactly-once order placement? | Idempotency key in request header. Order Service checks Redis for key before processing. Key expires after 5 minutes. Duplicate request returns cached response. |
| What about item substitution when out of stock? | After inventory lock fails for an item, suggest alternatives (same category, similar price) from ElasticSearch. Customer confirms substitution via push notification before packing continues. |
| How do you handle payment failures after inventory lock? | Inventory lock has a 5-minute TTL. If payment fails, release reserved inventory (decrement `reserved`, increment `quantity`). Saga pattern: inventory lock → payment → confirm, with compensating transactions on failure. |
| How would you add scheduled delivery (deliver at 7pm)? | Add `scheduled_at` to Order. Separate queue for scheduled orders. Cron job moves orders to packing queue 15 minutes before scheduled time. Rider assignment triggered at packing completion, same as instant. |

---

### Extensions

| Extension | Description |
|---|---|
| Multi-city expansion | City-level sharding for DB/cache/search, shared user service, regional API gateways |
| Dynamic pricing | Surge multiplier based on demand/supply ratio per dark store per time window |
| Subscription (pass) | Prepaid delivery pass with order count/month, stored as user entitlement, checked at checkout |
| Smart reordering | ML model predicts next order based on purchase history, pre-populates cart |
| Batched delivery | Combine 2-3 nearby orders for same rider, optimize route with TSP heuristic |
| Inventory forecasting | Time-series prediction of SKU demand per store, auto-trigger restocking from central warehouse |
| Dark store capacity management | Dynamic order throttling when packing queue exceeds threshold, redirect to neighboring store |
