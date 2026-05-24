# Amazon 10-Minute Delivery / Ultra-Fast Shipping — System Design Document

---

## 1. Functional Requirements

### Core Operations

1. `checkDeliveryEligibility(userId, itemIds[], addressId) → {eligible, eta, fulfillmentCenter}` — Determines if items qualify for 10-min delivery based on proximity to a micro-fulfillment center (MFC) with stock.
2. `placeExpressOrder(userId, itemIds[], addressId, paymentToken) → orderId` — Places an express order; atomically reserves inventory, captures payment, and enqueues for picking.
3. `orchestrateFulfillment(orderId) → {pickListId, packStationId, driverId}` — Coordinates the pick → pack → dispatch pipeline within the MFC.
4. `assignDriver(orderId, mfcId) → {driverId, vehicleType, eta}` — Matches nearest available driver to a ready-to-ship package.
5. `trackShipment(orderId) → {status, driverLocation, eta, timeline[]}` — Real-time shipment tracking from pick to doorstep.
6. `manageMFCInventory(mfcId, itemId, action, quantity) → confirmation` — Replenish, adjust, or audit inventory at a micro-fulfillment center.

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| What items qualify for 10-min delivery? | Curated catalog of ~3,000 high-velocity SKUs per MFC (groceries, essentials, electronics accessories). |
| MFC coverage radius? | 3-4 km radius per MFC, ~20 MFCs per metro city. |
| Order size limit? | Max 15 items or 10 kg per express order. |
| Is this Prime-only? | Yes, Prime members only for 10-min delivery. |
| Delivery fee? | Free above $15 order value, $2.99 below. Out of scope for system design. |
| Fallback if 10-min not possible? | Offer standard same-day delivery as fallback. |
| Driver fleet model? | Mix of gig drivers and Amazon Flex drivers with shift scheduling. |

### Assumptions

- 50M Prime members in a country, 5M in a metro city.
- 500K DAU use express delivery, 2 orders/day average during peak.
- Peak hours: 8am-11am, 5pm-9pm (8 hours total).
- 20 MFCs per metro city, each handling ~500 orders/hour at peak.
- Average pick time: 90 seconds. Pack time: 60 seconds. Drive time: 5-6 minutes.
- 3,000 SKUs per MFC, average 4 items per order.
- Each MFC has 25 pickers, 10 pack stations, 40 active drivers during peak.

---

## 2. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Scale | 5M DAU/city, 20 MFCs/city, 3K SKUs/MFC, 10K orders/hour/city peak |
| Throughput | ~2,800 orders/sec peak city-wide; 50K eligibility checks/sec (browse-time) |
| Latency | Eligibility check p50 < 20ms, p99 < 80ms; Order placement p50 < 300ms, p99 < 800ms; Tracking p50 < 50ms |
| Read/write ratio | 100:1 (eligibility checks dominate; orders are writes) |
| Consistency | Strong for inventory (prevent overselling); eventual OK for tracking, eligibility cache |
| Availability | 99.99% for eligibility + order placement (revenue-critical); 99.9% for tracking |
| Durability | Zero order/payment data loss; inventory reconciled within 5 minutes of discrepancy |
| Special constraints | Inventory reservation must be atomic + time-bounded (5-min TTL); order placement exactly-once via idempotency key; pick assignment must prevent double-pick |

### Back-of-Envelope Math

```
500K DAU × 2 orders/day = 1M orders/day per city
Peak multiplier 4× over 8 peak hours:
  = 4M order-equivalents / (8 × 3600) ≈ 139 orders/sec peak per city

Eligibility checks: each user checks ~20 items/session, 3 sessions/day
  = 500K × 20 × 3 / 86400 ≈ 347K/day avg
  Peak (10×): ~3.47M/hour ≈ 964 checks/sec

Per MFC at peak:
  = 139 orders/sec / 20 MFCs ≈ 7 orders/sec/MFC
  = 7 × 4 items = 28 inventory decrements/sec/MFC

Driver location updates: 20 MFCs × 40 drivers × 1 update/3sec = 267 writes/sec

Pick queue depth: 7 orders/sec × 90sec pick time = 630 concurrent picks/MFC
  → 25 pickers handle 630/25 = ~25 orders in pipeline per picker (queued)
  → Actual: 25 pickers × (3600/90) = 1,000 picks/hour capacity vs 500 orders/hour demand = 2× headroom
```

---

## 3. Core Entities

```
MicroFulfillmentCenter (MFC):
  mfc_id (PK) | name           | lat      | lng      | city    | radius_km | status  | capacity_orders_hr
  MFC-SEA-03   | Capitol Hill   | 47.6205  | -122.321 | Seattle | 3.5       | active  | 500
```

```
MFCInventory:
  mfc_id (PK) | item_id (SK) | quantity | reserved | bin_location | last_replenished
  MFC-SEA-03   | ASIN-B08X1   | 120      | 8        | A3-R2-S5     | 2026-03-13T06:00
```

```
Item (Catalog):
  item_id (PK) | title                | category    | price  | weight_g | dimensions_cm  | express_eligible
  ASIN-B08X1    | AA Batteries 8-pack  | electronics | 8.99   | 340      | 12x8x3         | true
```

```
Order:
  order_id (PK) | user_id  | mfc_id    | driver_id | status       | total  | placed_at           | promised_by         | delivered_at
  EXP-991204     | U-50821  | MFC-SEA-03| D-2091    | out_delivery | 22.47  | 2026-03-13T18:02:00 | 2026-03-13T18:12:00 | null
```

```
OrderItem:
  order_id (PK) | item_seq (SK) | item_id    | quantity | price_snapshot | bin_location
  EXP-991204     | 1             | ASIN-B08X1 | 1        | 8.99           | A3-R2-S5
```

```
PickTask:
  pick_task_id (PK) | order_id    | picker_id | status     | items_json          | created_at          | completed_at
  PT-44210            | EXP-991204  | PKR-107   | completed  | [{bin, item, qty}]  | 2026-03-13T18:02:05 | 2026-03-13T18:03:35
```

```
Driver:
  driver_id (PK) | name   | phone   | vehicle_type | current_mfc_id | status      | lat      | lng       | shift_end
  D-2091          | [name] | [phone] | bike         | MFC-SEA-03     | on_delivery | 47.6210  | -122.3195 | 2026-03-13T22:00
```

```
Cart (Redis — hot path):
  key: cart:{user_id}
  value: { mfcId, items: [{ itemId, qty, price }], expressEligible: bool, updatedAt }
  TTL: 2 hours
```

```
DriverLocation (Redis — hot path):
  GEOADD drivers:{mfc_id} {lng} {lat} {driver_id}
  HSET driver:{driver_id} status available order_id null last_seen {ts}
```

### Relationships

- MFC 1 → N MFCInventory (store-level stock per SKU)
- MFC 1 → N Drivers (shift-based), 1 → N PickTasks
- Order 1 → N OrderItems, 1 → 1 PickTask, N → 1 Driver
- MFCInventory links MFC ↔ Item (many-to-many with quantity + bin location)
- Cart is ephemeral (Redis), scoped to User + MFC

### Hot-Path vs Cold-Path Schema Differences

| Entity | Hot Path (Redis) | Cold Path (PostgreSQL) |
|---|---|---|
| Inventory | `HSET mfc_inv:{mfc_id}:{item_id} qty 120 reserved 8` — sub-ms reads | Full MFCInventory table with bin_location, audit trail |
| Driver Location | `GEOADD drivers:{mfc_id}` — geo queries | Driver table with last known lat/lng (updated async) |
| Pick Queue | `LPUSH pick_queue:{mfc_id}` — FIFO queue | PickTask table with full lifecycle |
| Cart | `HSET cart:{user_id}` — ephemeral | Not persisted (reconstructed from catalog if needed) |

---

## 4. API Routes

```
POST /express/eligibility
  Body: { itemIds: string[], addressId: string }
  Response: { eligible: bool, mfcId?: string, eta?: int, ineligibleItems?: string[], fallbackOption?: string }
  Errors: 400 (empty items), 404 (address not found)
  Auth: User JWT + Prime membership verified
  Note: Called on cart page load and before checkout. Cached 30s per (user, address, itemSet) hash.
```

```
POST /express/orders
  Headers: { Idempotency-Key: string }
  Body: { itemIds: [{itemId, qty}], addressId: string, paymentToken: string }
  Response: { orderId: string, status: "placed", promisedBy: string, mfcId: string }
  Errors: 402 (payment failed), 409 (inventory changed), 413 (exceeds weight/item limit), 429 (rate limited)
  Auth: User JWT + Prime
```

```
GET /express/orders/{orderId}/track
  Response: { status, timeline: [{stage, timestamp}], driver?: {name, lat, lng, vehicleType}, eta?: int }
  Errors: 404 (order not found), 403 (not your order)
  Auth: User JWT
  Note: WebSocket upgrade available at ws://host/express/orders/{orderId}/live
```

```
POST /internal/fulfillment/pick
  Body: { orderId: string, mfcId: string }
  Response: { pickTaskId: string, pickerId: string, bins: [{binLocation, itemId, qty}] }
  Errors: 503 (all pickers busy — queued)
  Auth: Internal service token
```

```
POST /internal/drivers/assign
  Body: { orderId: string, mfcId: string, packageWeight: int }
  Response: { driverId: string, vehicleType: string, estimatedPickupTime: int }
  Errors: 503 (no drivers available — retry queued)
  Auth: Internal service token
```

```
PATCH /internal/inventory/{mfcId}/{itemId}
  Body: { delta: int, reason: "order_reserve" | "order_cancel" | "restock" | "damage" | "audit" }
  Response: { available: int, reserved: int }
  Errors: 409 (insufficient stock), 404 (MFC/item not found)
  Auth: Internal service token or warehouse staff
```

```
POST /internal/inventory/replenish-request
  Body: { mfcId: string, items: [{itemId, requestedQty}] }
  Response: { replenishmentId: string, estimatedArrival: string }
  Auth: MFC manager or automated system
  Note: Triggers transfer from regional warehouse to MFC
```

---

## 5. High-Level Design

### Architecture Diagram

```
+----------------+     +----------------+     +------------------+
|  Customer App  |     |  Picker Device |     |  Driver App      |
|  (mobile/web)  |     |  (handheld)    |     |  (mobile)        |
+-------+--------+     +-------+--------+     +--------+---------+
        |                       |                       |
        +-----------+-----------+-----------+-----------+
                    |                       |
            +-------+--------+      +------+---------+
            |   API Gateway  |      | WebSocket GW   |
            | (auth, Prime   |      | (tracking,     |
            |  check, rate   |      |  picker feed)  |
            |  limit)        |      |                |
            +-------+--------+      +------+---------+
                    |                       |
    +---------------+--------+--------------+----------+
    |                        |                         |
+---+----------+    +--------+--------+    +-----------+---+
| Eligibility  |    |    Order        |    |   Tracking    |
| Service      |    |    Service      |    |   Service     |
+---+----------+    +--------+--------+    +-----------+---+
    |                        |                         |
    |               +--------+--------+                |
    |               | Fulfillment     |                |
    |               | Orchestrator    |                |
    |               +--+-----+----+--+                |
    |                  |     |    |                    |
    |            +-----+  +--+--+ +-----+             |
    |            |Pick |  |Pack | |Ship |             |
    |            |Svc  |  |Svc  | |Svc  |             |
    |            +-----+  +-----+ +--+--+             |
    |                                |                 |
    |               +----------------+                 |
    |               |  Driver Assignment               |
    |               |  Service                         |
    |               +----------------+                 |
    |                        |                         |
+---+----+   +---------+  +-+----------+  +-----------+---+
| Elastic|   |PostgreSQL|  |   Redis    |  | Kafka/SQS     |
| Search |   |(orders,  |  | (inventory |  | (events,      |
| (catalog|  | inventory|  |  cache,    |  |  fulfillment  |
|  + MFC) |  | , picks) |  |  driver    |  |  pipeline)    |
+---------+  +----------+  |  geo, cart)|  +--------------+
                            +-----------+
                                 |
                         +-------+--------+
                         | Notification   |
                         | Service (push, |
                         |  SMS, email)   |
                         +----------------+
```

### Component Responsibility

| Component | Role |
|---|---|
| Customer App | Browse express catalog, check eligibility, order, track |
| Picker Device | Receive pick lists, scan items, confirm picks, report issues |
| Driver App | Accept deliveries, navigate, update location, confirm delivery |
| API Gateway | JWT + Prime validation, rate limiting (500 req/min/user), geo-routing to nearest MFC cluster |
| Eligibility Service | Real-time check: is (items, address) serviceable by an MFC within 10 min? |
| Order Service | Order lifecycle: validate → reserve inventory → capture payment → enqueue fulfillment |
| Fulfillment Orchestrator | State machine: order_placed → picking → packing → ready_for_pickup → dispatched |
| Pick Service | Assigns pick tasks to pickers, optimizes pick path within MFC, tracks completion |
| Pack Service | Assigns pack station, validates items scanned match pick list, generates shipping label |
| Ship Service | Triggers driver assignment when package is ready, hands off to Driver Assignment |
| Driver Assignment Service | Nearest-available driver matching via Redis geo-index |
| Tracking Service | Aggregates fulfillment stage events + driver GPS into real-time timeline |
| ElasticSearch | Express catalog search filtered by MFC availability |
| PostgreSQL | Source of truth: orders, inventory, pick tasks, driver shifts |
| Redis | Hot-path inventory cache, driver geo-index, pick queues, carts, pub/sub for tracking |
| Kafka/SQS | Event bus: ORDER_PLACED → PICK_ASSIGNED → PICK_COMPLETE → PACKED → DISPATCHED → DELIVERED |

### Data Flow: Place an Express Order (Critical Path — 10-Minute Budget)

```
Time Budget:
  Order processing:  0:00 - 0:15  (15 sec)
  Picking:           0:15 - 1:45  (90 sec)
  Packing:           1:45 - 2:45  (60 sec)
  Driver assignment: 2:45 - 3:00  (15 sec)
  Driver to MFC:     3:00 - 4:00  (60 sec avg)
  Drive to customer: 4:00 - 9:30  (5.5 min avg)
  Handoff:           9:30 - 10:00 (30 sec)
  TOTAL:             10:00
```

1. Customer taps "Order Now" → `POST /express/orders` with idempotency key.
2. API Gateway validates JWT, confirms Prime membership, checks rate limit.
3. Order Service validates items, checks weight/count limits.
4. Order Service calls Inventory Service → atomic reserve (Redis cache + PostgreSQL write-behind).
5. Order Service calls Payment Service → captures payment.
   - If payment fails → release inventory reservation, return 402.
6. Order Service inserts Order + OrderItems into PostgreSQL.
7. Order Service publishes `ORDER_PLACED` event to Kafka.
8. Fulfillment Orchestrator consumes event → creates PickTask → pushes to `pick_queue:{mfc_id}` in Redis.
9. Pick Service assigns to next available picker → picker device shows optimized bin-walk route.
10. Picker scans items → Pick Service publishes `PICK_COMPLETE`.
11. Pack Service assigns pack station → packer verifies items, seals package → publishes `PACKED`.
12. Ship Service consumes `PACKED` → triggers Driver Assignment Service.
13. Driver Assignment: Redis GEORADIUS for nearest available driver + Lua atomic claim.
14. Driver notified via push → drives to MFC → scans package → publishes `DISPATCHED`.
15. Driver delivers to customer → customer confirms → publishes `DELIVERED`.
16. Each stage event → Tracking Service → Redis pub/sub → WebSocket to customer app.

### Technology Justification

| Choice | Why | Alternatives Considered |
|---|---|---|
| PostgreSQL | ACID for orders + inventory source of truth, row-level locking for concurrent reserves | DynamoDB (no multi-row txn), CockroachDB (more complex ops, overkill for single-city) |
| Redis | Sub-ms inventory cache, native geo for drivers, FIFO list for pick queues, pub/sub for tracking | Memcached (no geo/lists/pub-sub), PostgreSQL (too slow for hot path) |
| ElasticSearch | Full-text search + MFC-filtered catalog, ~3K docs/MFC | PostgreSQL FTS (slower), Algolia (vendor lock-in) |
| Kafka | Ordered event stream for fulfillment pipeline, replay capability for debugging | SQS (no ordering guarantee without FIFO, no replay), RabbitMQ (no replay) |
| WebSocket | Real-time tracking with sub-second updates | SSE (one-direction), polling (wastes bandwidth at scale) |

---

## 6. Deep Dives

### Deep Dive 1: Two-Tier Inventory — Redis Cache + PostgreSQL Source of Truth

**Problem**: Inventory checks happen at eligibility (964/sec) AND order placement (139/sec). Hitting PostgreSQL for every eligibility check is wasteful. But inventory must be strongly consistent for reservations to prevent overselling.

**Approach**: Read from Redis cache, write-through to both Redis and PostgreSQL.

```
+------------------+       +------------------+       +------------------+
| Eligibility Svc  |------>|   Redis Cache    |       |   PostgreSQL     |
| (reads only)     |  <1ms | mfc_inv:{mfc}:{item} |  |  MFCInventory    |
+------------------+       |  qty: 120        |       |  (source of truth)|
                           |  reserved: 8     |       +--------+---------+
+------------------+       +--------+---------+                |
| Order Service    |------>| Atomic reserve:  |  write-behind  |
| (reserve/release)|       | EVAL Lua script  +--------------->|
+------------------+       +------------------+                |
                                                               |
+------------------+       Reconciliation job (every 5 min) ---|
| Inventory Audit  |<-----------------------------------------+
+------------------+
```

**Reservation via Lua (atomic in Redis)**:

```python
RESERVE_SCRIPT = """
local key = KEYS[1]
local qty_needed = tonumber(ARGV[1])
local current_qty = tonumber(redis.call('HGET', key, 'qty') or 0)
local current_reserved = tonumber(redis.call('HGET', key, 'reserved') or 0)
local available = current_qty - current_reserved

if available >= qty_needed then
    redis.call('HINCRBY', key, 'reserved', qty_needed)
    return available - qty_needed  -- remaining available
else
    return -1  -- insufficient stock
end
"""

def reserve_inventory(mfc_id, items):
    reserved = []
    try:
        for item in items:
            key = f"mfc_inv:{mfc_id}:{item.item_id}"
            result = redis.eval(RESERVE_SCRIPT, keys=[key], args=[item.quantity])
            if result == -1:
                raise InsufficientStock(item.item_id)
            reserved.append(item)

        # Write-behind to PostgreSQL (async, batched every 100ms)
        enqueue_db_write(mfc_id, items, action="reserve")
        return True

    except InsufficientStock:
        # Rollback already-reserved items in Redis
        for r in reserved:
            key = f"mfc_inv:{mfc_id}:{r.item_id}"
            redis.hincrby(key, "reserved", -r.quantity)
        raise
```

**Reservation TTL**: Reserved items have a 5-minute TTL. If order isn't confirmed (payment fails, user abandons), a background job releases expired reservations:

```python
# Runs every 30 seconds
def release_expired_reservations():
    expired = db.query("""
        SELECT * FROM order_reservations
        WHERE status = 'reserved' AND created_at < NOW() - INTERVAL '5 minutes'
    """)
    for res in expired:
        redis.hincrby(f"mfc_inv:{res.mfc_id}:{res.item_id}", "reserved", -res.quantity)
        db.update(res.id, status="expired")
```

**Reconciliation**: Every 5 minutes, a job compares Redis inventory with PostgreSQL. Discrepancies (from crashes, network partitions) are auto-corrected by resetting Redis from PostgreSQL.

**Latency**: Eligibility check (Redis read): < 1ms. Reservation (Lua script): < 2ms per item. 4-item order: < 10ms total.

**Trade-off**: Redis is not durable — if Redis crashes, we lose in-flight reservations. Mitigation: AOF persistence + 5-minute reconciliation. Worst case: a few oversells during the ~5-minute window, handled by customer notification + refund.

> Interview One-Liner: "Redis Lua for atomic inventory reservation in < 2ms, PostgreSQL as source of truth with 5-minute reconciliation — oversell window bounded to minutes, not hours."

---

### Deep Dive 2: Fulfillment Orchestrator — State Machine with Latency Budgets

**Problem**: The 10-minute promise requires each stage (pick, pack, dispatch, deliver) to complete within strict time budgets. If picking takes 3 minutes instead of 90 seconds, the promise is broken.

**Approach**: Event-driven state machine with per-stage SLA monitoring and escalation.

**State Machine**:

```
+--------+    +--------+    +---------+    +----------+    +----------+    +-----------+
| placed |--->|picking |--->| packing |--->| ready_   |--->|dispatched|--->| delivered |
+--------+    +--------+    +---------+    | pickup   |    +----------+    +-----------+
    |             |              |         +----------+         |
    v             v              v              |               v
+--------+   +--------+    +---------+         v          +-----------+
|cancelled|  |pick_   |    |pack_    |    +---------+     |delivery_  |
+--------+   |failed  |    |failed   |    |no_driver|     |failed     |
             +--------+    +---------+    +---------+     +-----------+
```

**SLA Budget Enforcement**:

```python
STAGE_SLA = {
    "picking":    timedelta(seconds=120),   # 2 min (90s target + 30s buffer)
    "packing":    timedelta(seconds=90),    # 1.5 min (60s target + 30s buffer)
    "ready_pickup": timedelta(seconds=60),  # 1 min to assign driver
    "dispatched": timedelta(seconds=420),   # 7 min drive time max
}

def check_sla_breach(order):
    stage = order.status
    stage_start = order.stage_started_at
    elapsed = now() - stage_start
    sla = STAGE_SLA[stage]

    if elapsed > sla * 0.8:  # 80% of SLA — warning
        alert_mfc_manager(order, "approaching_sla_breach")

    if elapsed > sla:  # SLA breached
        escalate(order, stage)
        notify_customer(order, revised_eta=calculate_new_eta(order))
```

**Escalation actions by stage**:

| Stage | Escalation Action |
|---|---|
| picking (breach) | Reassign to faster picker, split pick list across 2 pickers |
| packing (breach) | Assign to priority pack station |
| ready_pickup (breach) | Expand driver search radius from 2km to 5km, offer surge bonus |
| dispatched (breach) | Notify customer of delay, offer credit |

**Kafka topic partitioning**: Orders partitioned by `mfc_id` → all events for one MFC processed in order by one consumer. Prevents race conditions in state transitions.

```
Topic: fulfillment-events
  Partition key: mfc_id
  Events: ORDER_PLACED, PICK_ASSIGNED, PICK_COMPLETE, PACK_COMPLETE, DRIVER_ASSIGNED, DISPATCHED, DELIVERED
```

**Latency**: State transition (Kafka consume → update DB → publish next event): < 50ms. SLA check runs as a side-effect of each transition.

**Trade-off**: Strict SLA enforcement means more escalations during peak, which can cascade (reassigning pickers disrupts other orders). Mitigation: maintain 2× picker capacity headroom. The 10-minute promise is a p90 target, not p100.

> Interview One-Liner: "Event-driven state machine with per-stage SLA budgets — breach at 80% triggers warning, 100% triggers escalation. Kafka partitioned by MFC for ordered processing."

---

### Deep Dive 3: Driver Assignment — Geo-Matching with Vehicle Optimization

**Problem**: When a package is ready, assign the nearest available driver whose vehicle can handle the package (bike for small, car for heavy/bulky). Must handle 40 drivers per MFC with sub-second assignment.

**Approach**: Redis geo-index per MFC, filtered by vehicle capability, with atomic Lua claim.

```python
VEHICLE_CAPACITY = {
    "bike":  {"max_weight_g": 5000,  "max_volume_cm3": 30000},
    "scooter": {"max_weight_g": 10000, "max_volume_cm3": 60000},
    "car":   {"max_weight_g": 25000, "max_volume_cm3": 200000},
}

def assign_driver(order_id, mfc_id, package_weight, package_volume):
    mfc = db.get(mfc_id)

    # Determine minimum vehicle type needed
    eligible_vehicles = [
        v for v, cap in VEHICLE_CAPACITY.items()
        if package_weight <= cap["max_weight_g"] and package_volume <= cap["max_volume_cm3"]
    ]
    # e.g., 3kg package → ["bike", "scooter", "car"]

    # Find nearest available drivers within 3km
    candidates = redis.georadius(
        f"drivers:{mfc_id}", mfc.lng, mfc.lat,
        radius=3, unit="km", sort="ASC", count=10
    )

    for driver_id, distance in candidates:
        driver_info = redis.hgetall(f"driver:{driver_id}")

        if driver_info["status"] != "available":
            continue
        if driver_info["vehicle_type"] not in eligible_vehicles:
            continue

        # Atomic claim via Lua
        claimed = redis.eval("""
            local status = redis.call('HGET', 'driver:' .. KEYS[1], 'status')
            if status == 'available' then
                redis.call('HMSET', 'driver:' .. KEYS[1],
                    'status', 'assigned',
                    'order_id', ARGV[1],
                    'assigned_at', ARGV[2])
                return 1
            end
            return 0
        """, keys=[driver_id], args=[order_id, str(now())])

        if claimed:
            db.update("orders", order_id, {
                "driver_id": driver_id,
                "status": "dispatched"
            })
            notify_driver(driver_id, order_id, mfc.address)
            return driver_id, driver_info["vehicle_type"]

    # No driver available — queue for retry
    enqueue_retry(order_id, mfc_id, delay_sec=10, max_retries=6)
    return None
```

**Driver location lifecycle**:

```
Driver starts shift → GEOADD drivers:{mfc_id} + HSET status=available
Driver assigned     → HSET status=assigned (stays in geo-index for tracking)
Driver picks up     → HSET status=on_delivery
Driver delivers     → HSET status=returning
Driver near MFC     → HSET status=available (ready for next order)
No heartbeat 30s    → HSET status=offline, ZREM from geo-index
```

**Latency**: GEORADIUS on 40 drivers + Lua claim: < 3ms. Full assignment including DB update + push: < 200ms.

**Trade-off**: Per-MFC driver pool is simpler but suboptimal at MFC boundaries. A driver 500m from MFC-A might be 200m from MFC-B's package. For v2: city-wide driver pool with MFC-proximity scoring. Adds complexity but improves utilization by ~15%.

> Interview One-Liner: "Redis GEORADIUS + vehicle-type filter + Lua atomic claim — driver assigned in < 3ms, no double-assignment, vehicle capacity matched to package."

---

### Deep Dive 4: Pick Path Optimization Inside the MFC

**Problem**: A picker has 90 seconds to collect 4 items from different bins in a warehouse. Random walking wastes time. Optimized path can save 20-30 seconds per order.

**Approach**: MFC layout modeled as a grid graph. Pick path computed as a greedy nearest-neighbor traversal.

**MFC Layout Model**:

```
MFC Floor Plan (simplified):

     Aisle 1    Aisle 2    Aisle 3    Aisle 4
    +--------+ +--------+ +--------+ +--------+
R5  | A1-R5  | | A2-R5  | | A3-R5  | | A4-R5  |  <- Frozen
R4  | A1-R4  | | A2-R4  | | A3-R4  | | A4-R4  |  <- Dairy
R3  | A1-R3  | | A2-R3  | | A3-R3  | | A4-R3  |  <- Snacks
R2  | A1-R2  | | A2-R2  | | A3-R2  | | A4-R2  |  <- Beverages
R1  | A1-R1  | | A2-R1  | | A3-R1  | | A4-R1  |  <- Electronics
    +--------+ +--------+ +--------+ +--------+
    ^                                           ^
    START (pick station)              END (pack station)
```

**Path computation**:

```python
def compute_pick_path(items_with_bins):
    """
    Greedy nearest-neighbor from start position.
    Bins are encoded as "A{aisle}-R{rack}-S{shelf}" → (aisle, rack) coordinates.
    """
    current = (0, 0)  # start position
    remaining = [(parse_bin(item.bin_location), item) for item in items_with_bins]
    path = []

    while remaining:
        # Find nearest unvisited bin
        nearest_idx = min(
            range(len(remaining)),
            key=lambda i: manhattan_distance(current, remaining[i][0])
        )
        coord, item = remaining.pop(nearest_idx)
        path.append({"bin": item.bin_location, "item": item.item_id, "qty": item.quantity})
        current = coord

    return path

def manhattan_distance(a, b):
    return abs(a[0] - b[0]) + abs(a[1] - b[1])
```

**Why greedy nearest-neighbor over TSP optimal**:
- 4 items → only 24 permutations. Could brute-force, but greedy is within 25% of optimal and computes in O(N^2) = 16 operations.
- For orders with 10+ items, greedy is significantly faster than exact TSP (NP-hard).
- Picker devices have limited compute — path must be ready in < 10ms.

**Bin placement strategy**: High-velocity items (milk, bread, eggs) placed near pack station (Aisle 1, Rack 1-2). Reduces average pick distance by ~30%.

```python
# Item velocity scoring for bin placement
def compute_bin_assignment(mfc_id):
    items = db.query("""
        SELECT item_id, COUNT(*) as order_count
        FROM order_items oi
        JOIN orders o ON oi.order_id = o.order_id
        WHERE o.mfc_id = :mfc_id AND o.placed_at > NOW() - INTERVAL '7 days'
        GROUP BY item_id
        ORDER BY order_count DESC
    """, mfc_id=mfc_id)

    # Top 20% items → Zone A (nearest to pack station)
    # Next 30% → Zone B
    # Bottom 50% → Zone C
    zones = {"A": items[:len(items)//5], "B": items[len(items)//5:len(items)//2], "C": items[len(items)//2:]}
    return zones
```

**Latency**: Path computation for 4 items: < 1ms. Sent to picker device as part of pick task assignment.

**Trade-off**: Greedy path isn't globally optimal, but the time saved by computing instantly vs. solving TSP exactly is worth the ~10% longer walk. At 4 items, the difference is typically 1-2 seconds.

> Interview One-Liner: "Greedy nearest-neighbor pick path in < 1ms — high-velocity items near pack station cuts average pick time by 30%."

---

### Deep Dive 5: Eligibility Check — Sub-20ms with Multi-Layer Caching

**Problem**: Every product page and cart view triggers an eligibility check. At 964 checks/sec peak, this must be blazing fast. But it depends on: user location → MFC mapping → per-MFC inventory → delivery feasibility.

**Approach**: Three-layer cache with progressive fallback.

```
Layer 1: CDN/Edge Cache (geohash → MFC mapping)
  TTL: 1 hour | Hit rate: 95% | Latency: < 5ms
  Key: geohash_6:{geohash} → mfc_id
  Invalidated on: MFC status change (rare)

Layer 2: Application Cache (MFC inventory availability bitmap)
  TTL: 30 seconds | Hit rate: 80% of L1 misses | Latency: < 2ms
  Key: mfc_avail:{mfc_id} → bitmap of available item_ids
  Updated: every 30s from Redis inventory

Layer 3: Redis (real-time inventory)
  TTL: none (live) | Latency: < 1ms
  Key: mfc_inv:{mfc_id}:{item_id} → {qty, reserved}
```

```python
def check_eligibility(item_ids, user_lat, user_lng):
    # Layer 1: Resolve MFC from geohash
    geohash = encode_geohash(user_lat, user_lng, precision=6)  # ~1.2km precision
    mfc_id = edge_cache.get(f"geohash_6:{geohash}")

    if not mfc_id:
        # Fallback: Redis GEORADIUS
        mfc_id = resolve_mfc_from_geo(user_lat, user_lng)
        edge_cache.set(f"geohash_6:{geohash}", mfc_id, ttl=3600)

    # Layer 2: Quick availability bitmap check
    avail_bitmap = app_cache.get(f"mfc_avail:{mfc_id}")
    if avail_bitmap:
        all_available = all(avail_bitmap.get(item_id) for item_id in item_ids)
        if all_available:
            return {"eligible": True, "mfcId": mfc_id, "eta": 10}

    # Layer 3: Real-time Redis check for uncertain items
    ineligible = []
    for item_id in item_ids:
        inv = redis.hgetall(f"mfc_inv:{mfc_id}:{item_id}")
        available = int(inv.get("qty", 0)) - int(inv.get("reserved", 0))
        if available <= 0:
            ineligible.append(item_id)

    if ineligible:
        return {"eligible": False, "ineligibleItems": ineligible, "fallbackOption": "same_day"}

    return {"eligible": True, "mfcId": mfc_id, "eta": 10}
```

**Geohash precision choice**: Precision 6 = ~1.2km × 0.6km cell. Within a 3km MFC radius, all cells in range map to the same MFC. Edge cases (cells on MFC boundary) resolved by GEORADIUS fallback.

**Bitmap refresh**: Every 30 seconds, a background job scans Redis inventory for each MFC and builds a bitmap: `{item_id: available > 0}`. This avoids per-item Redis calls for the common case (item in stock).

**Latency breakdown**:
- L1 hit (95%): geohash lookup 2ms + bitmap check 1ms = 3ms
- L2 hit (4%): + Redis geo 1ms = 4ms
- L3 (1%): + per-item Redis 1ms × 4 items = 8ms
- Weighted average: 0.95 × 3 + 0.04 × 4 + 0.01 × 8 = 3.09ms p50

**Trade-off**: 30-second bitmap staleness means a customer might see "eligible" but get a 409 at checkout if the last unit sold in that window. Acceptable: happens < 0.1% of orders, handled gracefully with "item just sold out" message + substitution suggestion.

> Interview One-Liner: "Three-layer cache — geohash edge cache at 95% hit rate, availability bitmap at 80%, Redis fallback. Weighted p50 = 3ms."

---

## 7. Closing Sections

### Complexity Summary

| Operation | Expected Latency | Big-O |
|---|---|---|
| Eligibility check | < 5ms p50, < 20ms p99 | O(K) items checked, mostly O(1) cache hits |
| Place express order | < 300ms p99 | O(K) inventory reserves + O(1) payment + O(1) DB insert |
| Inventory reservation (Redis Lua) | < 2ms per item | O(1) per HGET/HINCRBY |
| Driver assignment | < 3ms p99 | O(N+log N) GEORADIUS, N=40 drivers |
| Pick path computation | < 1ms | O(K^2) greedy nearest-neighbor, K=items (~4) |
| Real-time tracking push | < 100ms e2e | O(1) pub/sub + WebSocket |
| MFC resolution (geohash) | < 2ms (cache hit) | O(1) hash lookup |
| Fulfillment state transition | < 50ms | O(1) Kafka consume + DB update |

---

### Interview Walkthrough Pacing

| Section | Time | Notes |
|---|---|---|
| Requirements + clarifications | 3 min | Scope: single city, Prime-only, 3K SKUs/MFC, 10-min promise |
| Non-functional + math | 3 min | Derive 139 orders/sec, show time budget breakdown |
| Core entities + API | 5 min | Focus on MFCInventory (hot/cold), Order, PickTask |
| High-level design | 8 min | Draw diagram, walk through order→pick→pack→deliver flow |
| Deep dive: Two-tier inventory | 5 min | Lua reservation, write-behind, reconciliation |
| Deep dive: Fulfillment state machine | 4 min | SLA budgets, escalation, Kafka partitioning |
| Deep dive: Driver assignment | 4 min | Geo + vehicle filter + Lua claim |
| Deep dive: Pick path optimization | 3 min | Greedy NN, velocity-based bin placement |
| Deep dive: Eligibility caching | 3 min | Three-layer cache, geohash, bitmap |
| Trade-offs + extensions | 2 min | What breaks at 100 cities, what to optimize next |

---

### Follow-Up Questions

| Question | Answer |
|---|---|
| How do you handle an MFC going offline during peak? | Mark inactive in Redis (< 1s). In-flight orders: pickers complete current tasks, new orders routed to next-nearest MFC. Customer notified if ETA changes. Geo-cache invalidated for affected geohash cells. |
| What if the 10-minute promise can't be met? | SLA monitor detects breach risk at 80% of stage budget. Customer notified proactively with revised ETA. Offer credit/discount. Internally: escalation triggers (reassign picker, expand driver radius, surge bonus). |
| How do you prevent inventory drift between Redis and PostgreSQL? | Write-behind from Redis to PostgreSQL batched every 100ms. Reconciliation job every 5 minutes compares and corrects. Redis AOF for crash recovery. Worst-case drift window: 5 minutes. |
| How would you handle multi-city expansion? | Each city is an independent deployment: own PostgreSQL shard, Redis cluster, Kafka cluster, set of MFCs. Shared services: user auth, catalog, payment. City routing at API Gateway via geo-IP. |
| What about item substitution when something is out of stock? | At pick time, if bin is empty (physical stock mismatch), picker reports "item missing." System suggests substitute (same category, similar price) from ElasticSearch. Customer gets push notification to approve/reject. 30-second timeout → auto-substitute if pre-authorized. |
| How do you handle driver fraud (marking delivered without delivery)? | Photo proof of delivery required. GPS must be within 50m of delivery address at delivery time. Customer can dispute within 1 hour. Pattern detection flags drivers with high dispute rates. |
| How do you scale the pick operation during flash sales? | Pre-stage popular items in "grab-and-go" bins near pack station. Batch similar orders (3 orders needing milk → one trip to dairy aisle). Temporarily increase picker count with cross-trained staff. |
| What's the cold-start problem for a new MFC? | No historical velocity data for bin placement. Start with category-based placement (essentials near pack station). After 1 week of orders, run velocity analysis and re-slot bins. Converges to optimal layout in ~2 weeks. |

---

### Extensions

| Extension | Description |
|---|---|
| Multi-city deployment | City-level sharding with shared user/catalog services, geo-IP routing at edge |
| Predictive pre-staging | ML model predicts demand spikes (weather, events) → pre-position inventory at MFCs |
| Autonomous delivery | Drone/robot delivery for last-mile in low-density areas, reducing driver dependency |
| Dynamic delivery promise | ML-based ETA prediction using real-time MFC load, traffic, weather instead of fixed 10 min |
| Batch order optimization | Combine nearby orders for same driver, solve mini-TSP for multi-stop route |
| Dark store as a service | API for third-party sellers to fulfill from Amazon MFCs (marketplace model) |
| Carbon-neutral delivery | Route optimization for EV/bike preference, carbon tracking per delivery, customer-facing sustainability score |
| Real-time demand pricing | Surge pricing during peak demand, discount during off-peak to smooth load across hours |
