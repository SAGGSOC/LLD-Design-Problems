# Cross-Border Shipment Tracking Service — System Design

---

## 1. Requirements

### Functional Requirements

- `getTrackingStatus(trackingId) → currentStatus` — end user checks where their package is
- `getLocationHistory(trackingId) → events[]` — full journey timeline with locations and timestamps
- `ingestVendorUpdate(vendorId, payload) → ack` — third-party logistics vendors push status updates
- `getEstimatedDelivery(trackingId) → ETA` — predicted delivery date based on current leg + historical data
- `subscribeNotifications(trackingId, channel) → confirmation` — user opts into push/email/SMS updates
- `createShipment(orderId, origin, destination, legs[]) → trackingId` — e-commerce platform creates a shipment with planned route
- `updateShipmentLeg(trackingId, legId, vendorUpdate) → confirmation` — vendor updates a specific leg
- `getShipmentsByOrder(orderId) → shipments[]` — one order can have multiple packages

### Non-Functional Requirements

| Requirement | Target |
|---|---|
| Daily shipments tracked | 10M+ active |
| Vendor update ingestion rate | 50K events/sec peak |
| Read QPS (tracking page) | 200K+ (users refreshing) |
| Status query latency | < 100ms p99 |
| Event ingestion latency | < 5 sec (vendor push → visible to user) |
| Data retention | 2 years (regulatory for cross-border) |
| Availability | 99.95% |
| Consistency | Eventual OK (seconds-level delay acceptable) |

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| How many vendors? | 50-200 third-party logistics providers globally |
| Vendor integration format? | Webhook push (primary) + polling fallback |
| Transport modes? | Air, sea, road, rail — can mix within one shipment |
| Customs/border events? | Yes — customs clearance is a tracked milestone |
| Multi-package orders? | Yes — one order can split into multiple shipments |
| Real-time GPS tracking? | No — event-based milestones (picked up, in transit, at hub, etc.) |
| Who sees tracking? | End customer (limited view) + internal ops (full view) |
| Notification channels? | Push notification, email, SMS |
| International time zones? | All timestamps stored UTC, displayed in user's local TZ |

---

## 2. High-Level Architecture

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  Customer    │   │  E-Commerce  │   │  3P Vendor   │
│  App/Web     │   │  Platform    │   │  Systems     │
└──────┬───────┘   └──────┬───────┘   └──────┬───────┘
       │                  │                   │
       ▼                  ▼                   ▼
┌─────────────────────────────────────────────────────┐
│                   API Gateway                        │
│        (rate limiting, auth, vendor API keys)        │
└──────────────────────────┬──────────────────────────┘
                           │
       ┌───────────────────┼───────────────────┐
       ▼                   ▼                   ▼
┌─────────────┐   ┌──────────────┐   ┌──────────────────┐
│  Tracking   │   │  Ingestion   │   │  Shipment        │
│  Query      │   │  Service     │   │  Management      │
│  Service    │   │  (vendor     │   │  Service         │
│  (reads)    │   │   updates)   │   │  (create/plan)   │
└──────┬──────┘   └──────┬───────┘   └────────┬─────────┘
       │                 │                     │
       ▼                 ▼                     ▼
┌─────────────────────────────────────────────────────┐
│                    Data Layer                         │
├─────────────┬──────────────┬────────────────────────┤
│ DynamoDB    │ Redis        │ S3                      │
│ (shipments, │ (hot cache,  │ (archived events,       │
│  events,    │  latest      │  analytics)             │
│  legs)      │  status)     │                         │
├─────────────┴──────────────┴────────────────────────┤
│ Kafka / SQS                                          │
│ (event bus: vendor updates → processing pipeline)    │
└─────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────┐
│              Async Workers                           │
├─────────────┬──────────────┬────────────────────────┤
│ Notification│ ETA          │ Anomaly                 │
│ Service     │ Calculator   │ Detector                │
│ (push/email │ (ML-based    │ (stuck shipments,       │
│  /SMS)      │  prediction) │  route deviations)      │
└─────────────┴──────────────┴────────────────────────┘
```


---

## 3. Core Data Model

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              Entity Relationships                                │
│                                                                                  │
│  Order (1) ──────▶ (N) Shipment ──────▶ (N) ShipmentLeg                         │
│                          │                     │                                 │
│                          │                     ├──▶ (N) TrackingEvent            │
│                          │                     └──▶ (1) Vendor                   │
│                          │                                                       │
│                          ├──▶ (N) TrackingEvent  (shipment-level milestones)     │
│                          └──▶ (N) NotificationSubscription                       │
│                                                                                  │
│  Vendor (1) ──────▶ (N) ShipmentLeg                                              │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Entity Classes

```
┌──────────────────────────┐    ┌──────────────────────────┐
│       Shipment            │    │      ShipmentLeg          │
│──────────────────────────│    │──────────────────────────│
│ - trackingId: String      │    │ - legId: String           │
│ - orderId: String         │    │ - trackingId: String      │
│ - origin: Location        │    │ - legSequence: int        │
│ - destination: Location   │    │ - vendorId: String        │
│ - currentStatus: Status   │    │ - vendorTrackingRef: Str  │
│ - currentLegId: String    │    │ - transportMode: Mode     │
│ - totalLegs: int          │    │ - origin: Location        │
│ - estimatedDelivery: Inst │    │ - destination: Location   │
│ - actualDelivery: Instant │    │ - status: LegStatus       │
│ - createdAt: Instant      │    │ - estimatedDeparture: Ins │
│ - updatedAt: Instant      │    │ - actualDeparture: Inst   │
│ - customerId: String      │    │ - estimatedArrival: Inst  │
│ - weight: double          │    │ - actualArrival: Instant  │
│ - dimensions: Dimensions  │    │ - customsClearance:       │
└──────────────────────────┘    │     CustomsStatus         │
                                 │ - customsDetails: String  │
┌──────────────────────────┐    └──────────────────────────┘
│     TrackingEvent         │
│──────────────────────────│    ┌──────────────────────────┐
│ - eventId: String         │    │       Vendor              │
│ - trackingId: String      │    │──────────────────────────│
│ - legId: String (nullable)│    │ - vendorId: String        │
│ - eventType: EventType    │    │ - name: String            │
│ - status: String          │    │ - country: String         │
│ - location: Location      │    │ - webhookUrl: String      │
│ - timestamp: Instant      │    │ - apiKey: String          │
│ - vendorId: String        │    │ - supportedModes: List    │
│ - vendorRawPayload: Str   │    │ - pollingEndpoint: String │
│ - description: String     │    │ - isActive: boolean       │
│ - isCustomerVisible: bool │    │ - onboardedAt: Instant    │
└──────────────────────────┘    └──────────────────────────┘

┌──────────────────────────┐    ┌──────────────────────────┐
│       Location            │    │  NotificationSubscription │
│──────────────────────────│    │──────────────────────────│
│ - city: String            │    │ - subscriptionId: String  │
│ - country: String         │    │ - trackingId: String      │
│ - countryCode: String     │    │ - channel: Channel        │
│ - facility: String        │    │ - destination: String     │
│ - lat: double             │    │   (email/phone/deviceId)  │
│ - lng: double             │    │ - createdAt: Instant      │
└──────────────────────────┘    │ - isActive: boolean       │
                                 └──────────────────────────┘
┌──────────────────────────┐
│      Dimensions           │
│──────────────────────────│
│ - lengthCm: double        │
│ - widthCm: double         │
│ - heightCm: double        │
└──────────────────────────┘
```

---

## 4. Enums & State Machines

```java
public enum ShipmentStatus {
    CREATED,              // shipment record created, not yet picked up
    PICKED_UP,            // first vendor has the package
    IN_TRANSIT,           // moving between hubs/countries
    AT_CUSTOMS,           // held at border for customs clearance
    CUSTOMS_CLEARED,      // cleared customs, resuming transit
    OUT_FOR_DELIVERY,     // last-mile carrier has it
    DELIVERED,            // confirmed delivered
    FAILED_DELIVERY,      // delivery attempt failed
    RETURNED,             // returned to sender
    LOST                  // declared lost after investigation
}

public enum LegStatus {
    PLANNED,              // leg created but not started
    PICKED_UP,            // vendor picked up from origin
    IN_TRANSIT,           // moving
    AT_HUB,              // arrived at intermediate hub
    AT_CUSTOMS,           // at border checkpoint
    CUSTOMS_CLEARED,      // cleared, ready to continue
    CUSTOMS_HELD,         // held — documents needed
    DELIVERED_TO_NEXT,    // handed off to next leg's vendor
    COMPLETED,            // leg finished (final leg = delivered)
    FAILED                // leg failed (lost, damaged, etc.)
}

public enum TransportMode   { AIR, SEA, ROAD, RAIL }
public enum CustomsStatus   { NOT_APPLICABLE, PENDING, IN_REVIEW, CLEARED, HELD, REJECTED }
public enum EventType       { PICKUP, DEPARTURE, ARRIVAL, CUSTOMS_ENTRY, CUSTOMS_CLEARED,
                              CUSTOMS_HELD, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED,
                              FAILED_DELIVERY, RETURNED, EXCEPTION, INFO }
public enum Channel         { PUSH, EMAIL, SMS }
```

### Shipment Status State Machine

```
┌─────────┐     ┌───────────┐     ┌────────────┐     ┌─────────────┐
│ CREATED │────▶│ PICKED_UP │────▶│ IN_TRANSIT │────▶│ AT_CUSTOMS  │
└─────────┘     └───────────┘     └─────┬──────┘     └──────┬──────┘
                                        │                    │
                                        │              ┌─────▼────────────┐
                                        │              │ CUSTOMS_CLEARED  │
                                        │              └─────┬────────────┘
                                        │                    │
                                        ◀────────────────────┘
                                        │  (back to IN_TRANSIT for next leg)
                                        │
                                        ▼
                                 ┌──────────────────┐
                                 │ OUT_FOR_DELIVERY  │
                                 └───────┬──────────┘
                                         │
                              ┌──────────┼──────────┐
                              ▼          ▼          ▼
                       ┌───────────┐ ┌────────┐ ┌──────────────┐
                       │ DELIVERED │ │  LOST  │ │FAILED_DELIVERY│
                       └───────────┘ └────────┘ └──────┬───────┘
                                                       │
                                                       ▼
                                                ┌──────────┐
                                                │ RETURNED │
                                                └──────────┘
```

### Leg Status State Machine

```
┌─────────┐     ┌───────────┐     ┌────────────┐     ┌────────┐
│ PLANNED │────▶│ PICKED_UP │────▶│ IN_TRANSIT │────▶│ AT_HUB │
└─────────┘     └───────────┘     └─────┬──────┘     └────┬───┘
                                        │                  │
                                        ▼                  ▼
                                 ┌─────────────┐    ┌─────────────┐
                                 │ AT_CUSTOMS  │    │ IN_TRANSIT  │
                                 └──────┬──────┘    └─────────────┘
                                        │
                              ┌─────────┼──────────┐
                              ▼                    ▼
                     ┌─────────────────┐   ┌──────────────┐
                     │ CUSTOMS_CLEARED │   │ CUSTOMS_HELD │
                     └────────┬────────┘   └──────────────┘
                              │
                              ▼
                   ┌────────────────────┐
                   │ DELIVERED_TO_NEXT  │ (or COMPLETED if final leg)
                   └────────────────────┘

Any state ──▶ FAILED (exception path)
```

---

## 5. DynamoDB Table Design

### 5.1 Shipments Table (On-Demand)

```
Table Name: Shipments

PK: trackingId (String)     e.g. "TRK-20260315-A1B2C3"

Attributes:
  orderId           (S)     e.g. "ORD-98765"
  customerId        (S)     e.g. "CUST-12345"
  origin            (M)     {city: "Shanghai", country: "China", countryCode: "CN",
                             facility: "PVG-Warehouse-3", lat: 31.23, lng: 121.47}
  destination       (M)     {city: "New York", country: "USA", countryCode: "US",
                             facility: null, lat: 40.71, lng: -74.00}
  currentStatus     (S)     e.g. "IN_TRANSIT"
  currentLegId      (S)     e.g. "LEG-002"
  totalLegs         (N)     e.g. 3
  estimatedDelivery (S)     ISO-8601  e.g. "2026-03-22T18:00:00Z"
  actualDelivery    (S)     ISO-8601 (null until delivered)
  weight            (N)     e.g. 2.5  (kg)
  dimensions        (M)     {lengthCm: 30, widthCm: 20, heightCm: 15}
  createdAt         (S)     ISO-8601
  updatedAt         (S)     ISO-8601

GSI: OrderShipmentsIndex
  PK: orderId (S)
  SK: createdAt (S)
  → getShipmentsByOrder(orderId) — one order may have multiple packages

GSI: CustomerShipmentsIndex
  PK: customerId (S)
  SK: updatedAt (S)
  → "My Shipments" page — customer sees all their active/recent shipments

GSI: StatusIndex
  PK: currentStatus (S)
  SK: updatedAt (S)
  → Ops dashboard: all shipments AT_CUSTOMS, all FAILED_DELIVERY, etc.
```

### 5.2 TrackingEvents Table (On-Demand)

```
Table Name: TrackingEvents

PK: trackingId (String)     e.g. "TRK-20260315-A1B2C3"
SK: timestamp#eventId (S)   e.g. "2026-03-16T08:30:00Z#EVT-001"
    (timestamp prefix ensures chronological sort order)

Attributes:
  eventId           (S)     e.g. "EVT-001"
  legId             (S)     e.g. "LEG-001" (nullable for shipment-level events)
  eventType         (S)     e.g. "DEPARTURE"
  status            (S)     e.g. "IN_TRANSIT"
  location          (M)     {city: "Shanghai", country: "China", countryCode: "CN",
                             facility: "PVG-Air-Cargo"}
  vendorId          (S)     e.g. "VND-dhl-express"
  description       (S)     e.g. "Departed Shanghai Pudong International Airport"
  vendorRawPayload  (S)     raw JSON from vendor (for debugging/audit)
  isCustomerVisible (BOOL)  e.g. true
  processedAt       (S)     ISO-8601 (when our system processed it)

→ Query by trackingId, sorted by timestamp — gives full journey timeline
→ ScanIndexForward=false for latest-first

GSI: LegEventsIndex
  PK: trackingId#legId (S)  e.g. "TRK-20260315-A1B2C3#LEG-001"
  SK: timestamp (S)
  → All events for a specific leg of a shipment
```

### 5.3 ShipmentLegs Table (On-Demand)

```
Table Name: ShipmentLegs

PK: trackingId (String)     e.g. "TRK-20260315-A1B2C3"
SK: legSequence (Number)    e.g. 1

Attributes:
  legId               (S)   e.g. "LEG-001"
  vendorId            (S)   e.g. "VND-dhl-express"
  vendorTrackingRef   (S)   e.g. "DHL-7890123456" (vendor's own tracking number)
  transportMode       (S)   e.g. "AIR"
  origin              (M)   {city: "Shanghai", country: "China", ...}
  destination         (M)   {city: "Los Angeles", country: "USA", ...}
  status              (S)   e.g. "COMPLETED"
  estimatedDeparture  (S)   ISO-8601
  actualDeparture     (S)   ISO-8601
  estimatedArrival    (S)   ISO-8601
  actualArrival       (S)   ISO-8601
  customsClearance    (S)   e.g. "CLEARED"
  customsDetails      (S)   e.g. "Cleared at LAX customs, duty paid $12.50"

→ Query by trackingId, sorted by legSequence — gives ordered route plan

GSI: VendorLegIndex
  PK: vendorId (S)
  SK: trackingId (S)
  → Vendor dashboard: all legs handled by a specific vendor

GSI: VendorRefIndex
  PK: vendorId#vendorTrackingRef (S)   e.g. "VND-dhl-express#DHL-7890123456"
  → Vendor pushes update with their own tracking ref — we resolve to our trackingId + legId
  → This is the critical lookup for inbound vendor webhooks
```

### 5.4 Vendors Table (On-Demand)

```
Table Name: Vendors

PK: vendorId (String)       e.g. "VND-dhl-express"

Attributes:
  name              (S)     e.g. "DHL Express"
  country           (S)     e.g. "DE" (headquarters)
  webhookUrl        (S)     e.g. "https://api.dhl.com/tracking/webhook"
  apiKeyHash        (S)     hashed API key for authenticating inbound webhooks
  supportedModes    (SS)    e.g. {"AIR", "ROAD"}
  pollingEndpoint   (S)     e.g. "https://api.dhl.com/tracking/v1/shipments/{ref}"
  pollingInterval   (N)     e.g. 300 (seconds — fallback polling frequency)
  isActive          (BOOL)  e.g. true
  onboardedAt       (S)     ISO-8601
  payloadFormat     (S)     e.g. "DHL_V2" (which normalizer to use)

→ Simple key-value lookup by vendorId
→ Small table (~200 rows), cached in memory at service startup
```

### 5.5 NotificationSubscriptions Table (On-Demand)

```
Table Name: NotificationSubscriptions

PK: trackingId (String)     e.g. "TRK-20260315-A1B2C3"
SK: channel#destination (S) e.g. "EMAIL#user@example.com"

Attributes:
  subscriptionId    (S)     e.g. "SUB-abc123"
  channel           (S)     e.g. "EMAIL"
  destination       (S)     e.g. "user@example.com"
  isActive          (BOOL)  e.g. true
  createdAt         (S)     ISO-8601

→ Query by trackingId — get all notification channels for a shipment
→ When a tracking event arrives, fan out to all active subscriptions

GSI: CustomerNotificationsIndex
  PK: destination (S)       e.g. "user@example.com"
  SK: createdAt (S)
  → User manages all their notification subscriptions across shipments
```

### DynamoDB Table Summary

```
┌──────────────────────────┬──────────────────┬──────────────────┬──────────────────────────────┐
│ Table                    │ PK               │ SK               │ GSIs                         │
├──────────────────────────┼──────────────────┼──────────────────┼──────────────────────────────┤
│ Shipments                │ trackingId       │ —                │ OrderShipmentsIndex,         │
│                          │                  │                  │ CustomerShipmentsIndex,      │
│                          │                  │                  │ StatusIndex                  │
├──────────────────────────┼──────────────────┼──────────────────┼──────────────────────────────┤
│ TrackingEvents           │ trackingId       │ timestamp#evtId  │ LegEventsIndex               │
├──────────────────────────┼──────────────────┼──────────────────┼──────────────────────────────┤
│ ShipmentLegs             │ trackingId       │ legSequence      │ VendorLegIndex,              │
│                          │                  │                  │ VendorRefIndex               │
├──────────────────────────┼──────────────────┼──────────────────┼──────────────────────────────┤
│ Vendors                  │ vendorId         │ —                │ —                            │
├──────────────────────────┼──────────────────┼──────────────────┼──────────────────────────────┤
│ NotificationSubscriptions│ trackingId       │ channel#dest     │ CustomerNotificationsIndex   │
└──────────────────────────┴──────────────────┴──────────────────┴──────────────────────────────┘

All tables use On-Demand capacity mode — cross-border shipment traffic is
spiky (holiday seasons, flash sales) and varies by region/time zone.
```


---

## 6. Flow Diagrams

### Flow 1: Shipment Creation (E-Commerce Platform)

```
E-commerce order placed, items split into packages
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ createShipment(orderId, origin, destination, legs[]) │
│                                                      │
│ Input example:                                       │
│   orderId: "ORD-98765"                               │
│   origin: Shanghai, CN                               │
│   destination: New York, US                          │
│   legs: [                                            │
│     {vendor: DHL, mode: AIR, SH → LA},              │
│     {vendor: USPS, mode: ROAD, LA → NY}             │
│   ]                                                  │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│ Generate trackingId: "TRK-20260315-A1B2C3"          │
│                                                      │
│ 1. Write Shipments table                             │
│    status = CREATED, totalLegs = 2                   │
│                                                      │
│ 2. Write ShipmentLegs table (2 rows)                 │
│    LEG-001: DHL, AIR, SH→LA, status=PLANNED         │
│    LEG-002: USPS, ROAD, LA→NY, status=PLANNED       │
│                                                      │
│ 3. Write TrackingEvents table                        │
│    eventType=INFO, "Shipment created"                │
│                                                      │
│ 4. Publish to Kafka: SHIPMENT_CREATED event          │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│ Async: Notify vendor DHL about upcoming pickup       │
│ Async: Calculate initial ETA based on route          │
│ Async: Create default notification subscription      │
│        for customer (email from order)               │
└──────────────────────────────────────────────────────┘
```

### Flow 2: Vendor Update Ingestion (Webhook)

```
DHL pushes webhook: "Package departed Shanghai"
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ API Gateway receives POST /vendor/webhook            │
│                                                      │
│ 1. Authenticate: validate API key → vendorId         │
│ 2. Rate limit: per-vendor throttle                   │
│ 3. Idempotency check: hash(vendorId + payload +     │
│    timestamp) → check Redis dedup key (TTL 24h)     │
│    If duplicate → return 200 OK (already processed) │
└────────────────────┬─────────────────────────────────┘
                     │ (new event)
                     ▼
┌──────────────────────────────────────────────────────┐
│ Ingestion Service                                    │
│                                                      │
│ 1. Parse vendor-specific payload                     │
│    (each vendor has its own format)                  │
│                                                      │
│ 2. Normalize to internal TrackingEvent:              │
│    vendorTrackingRef: "DHL-7890123456"               │
│    → Lookup VendorRefIndex GSI                       │
│    → Resolve to trackingId + legId                   │
│                                                      │
│ 3. Map vendor status to our EventType + LegStatus    │
│    DHL "shipment.departure" → DEPARTURE, IN_TRANSIT  │
│                                                      │
│ 4. Publish normalized event to Kafka topic           │
│    topic: tracking-events                            │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│ Event Processor (Kafka consumer)                     │
│                                                      │
│ 1. Write TrackingEvents table                        │
│    PK=trackingId, SK=timestamp#eventId               │
│                                                      │
│ 2. Update ShipmentLegs table                         │
│    LEG-001: status → IN_TRANSIT,                     │
│    actualDeparture = event timestamp                 │
│                                                      │
│ 3. Update Shipments table                            │
│    currentStatus → IN_TRANSIT,                       │
│    currentLegId = LEG-001, updatedAt = now           │
│                                                      │
│ 4. Update Redis cache                                │
│    key: "tracking:{trackingId}"                      │
│    value: latest status + location + ETA             │
│    TTL: 1 hour                                       │
│                                                      │
│ 5. Publish to notification topic                     │
│    → triggers notification fan-out                   │
│                                                      │
│ 6. Publish to ETA topic                              │
│    → triggers ETA recalculation                      │
└──────────────────────────────────────────────────────┘
```

### Flow 3: User Tracking Query

```
Customer opens tracking page with trackingId
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ getTrackingStatus(trackingId)                        │
│                                                      │
│ Step 1: Check Redis cache                            │
│   key: "tracking:TRK-20260315-A1B2C3"               │
│                                                      │
│   Cache HIT → return immediately (< 5ms)            │
│   Cache MISS → Step 2                                │
└────────────────────┬─────────────────────────────────┘
                     │ (cache miss)
                     ▼
┌──────────────────────────────────────────────────────┐
│ Step 2: Read from DynamoDB                           │
│                                                      │
│ Parallel reads:                                      │
│   a) Shipments table: GetItem(trackingId)            │
│      → currentStatus, origin, destination, ETA       │
│                                                      │
│   b) ShipmentLegs table: Query(trackingId)           │
│      → all legs with statuses, transport modes       │
│                                                      │
│   c) TrackingEvents table: Query(trackingId,         │
│        ScanIndexForward=false, Limit=10)             │
│      → latest 10 events for timeline                 │
│      → filter: isCustomerVisible = true              │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│ Step 3: Assemble response                            │
│                                                      │
│ {                                                    │
│   trackingId: "TRK-20260315-A1B2C3",                │
│   status: "IN_TRANSIT",                              │
│   origin: "Shanghai, CN",                            │
│   destination: "New York, US",                       │
│   estimatedDelivery: "2026-03-22",                   │
│   currentLocation: "Los Angeles, US",                │
│   legs: [                                            │
│     {leg: 1, SH→LA, AIR, COMPLETED},                │
│     {leg: 2, LA→NY, ROAD, IN_TRANSIT}               │
│   ],                                                 │
│   timeline: [                                        │
│     {time: "Mar 18", "Arrived Los Angeles"},         │
│     {time: "Mar 16", "Departed Shanghai"},           │
│     {time: "Mar 15", "Picked up in Shanghai"}        │
│   ]                                                  │
│ }                                                    │
│                                                      │
│ Step 4: Populate Redis cache for next request        │
│   TTL: 1 hour (or 5 min if OUT_FOR_DELIVERY)        │
└──────────────────────────────────────────────────────┘
```

### Flow 4: Customs Clearance

```
Shipment arrives at border (e.g., LAX customs)
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ Vendor pushes: "Arrived at customs"                  │
│                                                      │
│ Ingestion normalizes:                                │
│   eventType = CUSTOMS_ENTRY                          │
│   legStatus → AT_CUSTOMS                             │
│   shipmentStatus → AT_CUSTOMS                        │
│                                                      │
│ Write: TrackingEvents, ShipmentLegs, Shipments       │
│ Notify customer: "Your package is at customs"        │
└────────────────────┬─────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ▼                         ▼
┌────────────────┐       ┌────────────────────┐
│ Customs CLEARS │       │ Customs HOLDS      │
│                │       │                    │
│ Vendor pushes: │       │ Vendor pushes:     │
│ "Cleared"      │       │ "Held — documents  │
│                │       │  required"         │
│ legStatus →    │       │                    │
│ CUSTOMS_CLEARED│       │ legStatus →        │
│                │       │ CUSTOMS_HELD       │
│ shipmentStatus │       │                    │
│ → CUSTOMS_     │       │ customsClearance → │
│   CLEARED      │       │ HELD               │
│                │       │                    │
│ Resume transit │       │ Notify customer:   │
│ to next hub    │       │ "Action needed —   │
│                │       │  customs hold"     │
│                │       │                    │
│                │       │ Anomaly detector   │
│                │       │ flags for ops      │
│                │       │ review if held     │
│                │       │ > 48 hours         │
└────────────────┘       └────────────────────┘
```

### Flow 5: Leg Handoff (Vendor-to-Vendor Transition)

```
Leg 1 (DHL, AIR, SH→LA) completing
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ DHL pushes: "Delivered to destination facility"      │
│                                                      │
│ Event Processor:                                     │
│ 1. Update LEG-001:                                   │
│    status → DELIVERED_TO_NEXT                        │
│    actualArrival = now                               │
│                                                      │
│ 2. Advance shipment to next leg:                     │
│    currentLegId → LEG-002                            │
│    currentStatus stays IN_TRANSIT                    │
│                                                      │
│ 3. Write TrackingEvent:                              │
│    "Arrived at Los Angeles — transferring to         │
│     local carrier"                                   │
│                                                      │
│ 4. Notify next vendor (USPS):                        │
│    "Package ready for pickup at LAX facility"        │
│    via vendor's API / webhook                        │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│ USPS picks up → pushes: "Picked up"                 │
│                                                      │
│ Event Processor:                                     │
│ 1. Update LEG-002:                                   │
│    status → PICKED_UP                                │
│    actualDeparture = now                             │
│                                                      │
│ 2. Write TrackingEvent:                              │
│    "Package picked up by local carrier in LA"        │
│                                                      │
│ 3. Recalculate ETA based on USPS historical data    │
│    for LA → NY road transit                          │
└──────────────────────────────────────────────────────┘
```

### Flow 6: Notification Fan-Out

```
New tracking event processed
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ Event Processor publishes to Kafka:                  │
│ topic: notification-events                           │
│                                                      │
│ Payload:                                             │
│   trackingId, eventType, status, description,        │
│   isCustomerVisible                                  │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│ Notification Service (Kafka consumer)                │
│                                                      │
│ 1. Skip if isCustomerVisible = false                 │
│    (internal ops events don't notify customers)      │
│                                                      │
│ 2. Query NotificationSubscriptions table             │
│    PK = trackingId → get all active subscriptions    │
│                                                      │
│ 3. For each subscription, dispatch:                  │
│    ┌─────────┬──────────────────────────────┐        │
│    │ Channel │ Action                       │        │
│    ├─────────┼──────────────────────────────┤        │
│    │ PUSH    │ Send to FCM/APNs via device  │        │
│    │         │ token                        │        │
│    │ EMAIL   │ Enqueue to SES with template │        │
│    │ SMS     │ Enqueue to SNS               │        │
│    └─────────┴──────────────────────────────┘        │
│                                                      │
│ 4. Rate limit: max 1 notification per trackingId     │
│    per channel per 30 minutes (avoid spam on         │
│    rapid vendor updates)                             │
└──────────────────────────────────────────────────────┘
```


---

## 7. DDB Write Triggers — Who Writes What & When

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                              DDB WRITE MAP BY ACTION                                     │
├────────────────────────┬────────────────┬───────────────────────┬────────────────────────┤
│ Action                 │ Triggered By   │ Tables Written        │ Write Type             │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ createShipment(order,  │ E-Commerce     │ Shipments             │ PutItem (new shipment) │
│   origin, dest, legs)  │ Platform       │ ShipmentLegs          │ PutItem per leg        │
│                        │                │ TrackingEvents        │ PutItem (CREATED event)│
│                        │                │ NotificationSubs      │ PutItem (default sub)  │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ ingestVendorUpdate     │ 3P Vendor      │ TrackingEvents        │ PutItem (new event)    │
│ (webhook)              │ (via webhook)  │ ShipmentLegs          │ UpdateItem (status,    │
│                        │                │                       │   timestamps)          │
│                        │                │ Shipments             │ UpdateItem (status,    │
│                        │                │                       │   currentLegId, ETA)   │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ ingestVendorUpdate     │ Polling Worker │ (same as webhook)     │ (same as webhook)      │
│ (polling fallback)     │ (async)        │                       │                        │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ subscribeNotifications │ Customer       │ NotificationSubs      │ PutItem (new sub)      │
│ (trackingId, channel)  │                │                       │                        │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ unsubscribe            │ Customer       │ NotificationSubs      │ UpdateItem             │
│ (subscriptionId)       │                │                       │   (isActive=false)     │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ updateETA              │ ETA Calculator │ Shipments             │ UpdateItem             │
│ (trackingId, newETA)   │ (async worker) │                       │   (estimatedDelivery)  │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ markDelivered          │ Event Processor│ Shipments             │ UpdateItem (status →   │
│ (trackingId)           │ (from vendor   │                       │   DELIVERED,           │
│                        │  update)       │                       │   actualDelivery=now)  │
│                        │                │ ShipmentLegs          │ UpdateItem (last leg   │
│                        │                │                       │   status → COMPLETED)  │
│                        │                │ TrackingEvents        │ PutItem (DELIVERED)    │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ legHandoff             │ Event Processor│ ShipmentLegs          │ UpdateItem (prev leg   │
│ (prev leg completes,   │                │                       │   → DELIVERED_TO_NEXT) │
│  next leg starts)      │                │ Shipments             │ UpdateItem             │
│                        │                │                       │   (currentLegId)       │
│                        │                │ TrackingEvents        │ PutItem (handoff event)│
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ customsClearance       │ Event Processor│ ShipmentLegs          │ UpdateItem             │
│ (cleared or held)      │ (from vendor   │                       │   (customsClearance,   │
│                        │  update)       │                       │    customsDetails)     │
│                        │                │ Shipments             │ UpdateItem (status)    │
│                        │                │ TrackingEvents        │ PutItem (customs event)│
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ registerVendor         │ Ops / Admin    │ Vendors               │ PutItem (new vendor)   │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ updateVendor           │ Ops / Admin    │ Vendors               │ UpdateItem (config     │
│ (vendorId, changes)    │                │                       │   changes)             │
├────────────────────────┼────────────────┼───────────────────────┼────────────────────────┤
│ deactivateVendor       │ Ops / Admin    │ Vendors               │ UpdateItem             │
│ (vendorId)             │                │                       │   (isActive=false)     │
└────────────────────────┴────────────────┴───────────────────────┴────────────────────────┘
```

### Write Flow Per Table — Visual

```
Shipments               ◀── createShipment (PutItem)
                        ◀── ingestVendorUpdate (UpdateItem: status, currentLegId, updatedAt)
                        ◀── updateETA (UpdateItem: estimatedDelivery)
                        ◀── markDelivered (UpdateItem: status=DELIVERED, actualDelivery)
                        ◀── legHandoff (UpdateItem: currentLegId)
                        ◀── customsClearance (UpdateItem: status)

TrackingEvents          ◀── createShipment (PutItem: CREATED event)
                        ◀── ingestVendorUpdate (PutItem: every vendor event)
                        ◀── markDelivered (PutItem: DELIVERED event)
                        ◀── legHandoff (PutItem: handoff event)
                        ◀── customsClearance (PutItem: customs event)
                        (append-only — events are never updated or deleted)

ShipmentLegs            ◀── createShipment (PutItem per leg: status=PLANNED)
                        ◀── ingestVendorUpdate (UpdateItem: status, timestamps)
                        ◀── legHandoff (UpdateItem: prev leg → DELIVERED_TO_NEXT)
                        ◀── customsClearance (UpdateItem: customsClearance, customsDetails)
                        ◀── markDelivered (UpdateItem: last leg → COMPLETED)

Vendors                 ◀── registerVendor (PutItem)
                        ◀── updateVendor (UpdateItem)
                        ◀── deactivateVendor (UpdateItem: isActive=false)

NotificationSubscriptions ◀── createShipment (PutItem: default email sub)
                          ◀── subscribeNotifications (PutItem)
                          ◀── unsubscribe (UpdateItem: isActive=false)
```

### Conditional Writes & Consistency Notes

```
1. ingestVendorUpdate uses idempotency key in Redis:
   key: "dedup:{hash(vendorId + payload + timestamp)}", TTL: 24h
   → If key exists, skip processing (vendor retried the webhook)
   → Write to TrackingEvents uses ConditionExpression:
     "attribute_not_exists(PK) AND attribute_not_exists(SK)"
     to prevent duplicate events at the DDB level as a second guard

2. Leg status transitions use conditional writes:
   ConditionExpression: "status = :expectedPrevious"
   → PLANNED → PICKED_UP → IN_TRANSIT → ... (enforces state machine)
   → Prevents out-of-order vendor updates from corrupting state

3. Shipment status update uses a version counter:
   ConditionExpression: "version = :currentVersion"
   SET version = version + 1
   → Prevents concurrent vendor updates from overwriting each other
   → On conflict, re-read and re-apply (optimistic locking)

4. createShipment uses TransactWriteItems:
   → Atomically writes Shipments + all ShipmentLegs + initial event
   → Ensures no partial shipment creation

5. TrackingEvents is append-only:
   → Events are never updated or deleted
   → Natural audit trail — every vendor update is permanently recorded
   → vendorRawPayload preserved for dispute resolution

6. Redis cache is write-through on event processing:
   → After DDB writes succeed, update Redis
   → If Redis write fails, cache will self-heal on next read (cache-aside)
   → TTL-based expiry ensures stale data is bounded
```


---

## 8. Vendor Integration

### Webhook Ingestion Format

```
POST /api/v1/vendor/webhook
Headers:
  X-Vendor-Id: VND-dhl-express
  X-Api-Key: <vendor-specific-key>
  X-Idempotency-Key: <vendor-generated-uuid>
  Content-Type: application/json

Body (vendor-specific — example DHL format):
{
  "trackingNumber": "DHL-7890123456",
  "event": "shipment.departure",
  "timestamp": "2026-03-16T08:30:00Z",
  "location": {
    "city": "Shanghai",
    "country": "CN",
    "facility": "PVG-Air-Cargo"
  },
  "details": "Departed Shanghai Pudong International Airport"
}
```

### Vendor Payload Normalization

```
┌──────────────────────────────────────────────────────┐
│              Normalization Pipeline                   │
│                                                      │
│  Raw Vendor Payload                                  │
│       │                                              │
│       ▼                                              │
│  ┌──────────────────┐                                │
│  │ Vendor Adapter   │  (one per vendor format)       │
│  │ Registry         │                                │
│  │                  │  DHL_V2 → DhlNormalizer         │
│  │                  │  FEDEX_V3 → FedexNormalizer     │
│  │                  │  USPS_V1 → UspsNormalizer       │
│  │                  │  GENERIC → GenericNormalizer     │
│  └────────┬─────────┘                                │
│           │                                          │
│           ▼                                          │
│  ┌──────────────────┐                                │
│  │ Normalized Event │                                │
│  │                  │                                │
│  │ trackingId       │  (resolved via VendorRefIndex) │
│  │ legId            │                                │
│  │ eventType        │  (mapped from vendor status)   │
│  │ legStatus        │                                │
│  │ location         │  (standardized format)         │
│  │ timestamp        │  (UTC)                         │
│  │ description      │  (human-readable)              │
│  │ vendorRawPayload │  (preserved for audit)         │
│  └──────────────────┘                                │
└──────────────────────────────────────────────────────┘
```

### Polling Fallback

```
Not all vendors support webhooks reliably. For those:

┌──────────────────────────────────────────────────────┐
│ Polling Worker (scheduled, per vendor)               │
│                                                      │
│ 1. Query ShipmentLegs by VendorLegIndex              │
│    where vendorId = target vendor                    │
│    and status NOT IN (COMPLETED, FAILED)             │
│                                                      │
│ 2. For each active leg:                              │
│    GET vendor's polling endpoint with                │
│    vendorTrackingRef                                 │
│                                                      │
│ 3. Compare response to last known state              │
│    If changed → feed into same normalization         │
│    pipeline as webhook path                          │
│                                                      │
│ Frequency: configurable per vendor                   │
│   Default: every 5 minutes for active legs           │
│   Reduced: every 30 min for PLANNED legs             │
│                                                      │
│ Backoff: if vendor API returns 429/5xx,              │
│   exponential backoff with jitter                    │
└──────────────────────────────────────────────────────┘
```

---

## 9. Caching Strategy

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Redis Cache Layer                            │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Key Pattern              │ Value                  │ TTL             │
│  ─────────────────────────┼────────────────────────┼─────────────── │
│  tracking:{trackingId}    │ JSON: latest status,   │ 1 hour         │
│                           │ location, ETA, legs    │ (5 min if      │
│                           │ summary                │  OUT_FOR_       │
│                           │                        │  DELIVERY)     │
│  ─────────────────────────┼────────────────────────┼─────────────── │
│  dedup:{hash}             │ "1" (exists check)     │ 24 hours       │
│                           │                        │                │
│  ─────────────────────────┼────────────────────────┼─────────────── │
│  vendor:{vendorId}        │ Vendor config JSON     │ 1 hour         │
│                           │                        │                │
│  ─────────────────────────┼────────────────────────┼─────────────── │
│  eta:{trackingId}         │ ETA calculation result │ 30 min         │
│                           │                        │                │
└──────────────────────────────────────────────────────────────────────┘

Write-through: Event Processor updates Redis after DDB writes succeed.
Cache-aside:   Tracking Query Service reads Redis first, falls back to DDB.
Invalidation:  Every vendor update refreshes the tracking:{trackingId} key.

Why shorter TTL for OUT_FOR_DELIVERY?
  → Users refresh obsessively during last-mile delivery.
  → Status changes rapidly (out → delivered in minutes).
  → 5-min TTL balances freshness vs. DDB read cost.

Expected cache hit rate: ~85-90%
  → Most tracking page views are repeat refreshes of the same shipment.
  → Hot shipments (recently updated) stay warm in cache.
```

---

## 10. ETA Calculation

```
┌──────────────────────────────────────────────────────┐
│ ETA Calculator (Async Worker)                        │
│                                                      │
│ Triggered by: every tracking event on Kafka          │
│                                                      │
│ Inputs:                                              │
│   - Current leg + remaining legs from ShipmentLegs   │
│   - Transport mode per leg (AIR, SEA, ROAD, RAIL)    │
│   - Historical transit times for same route/vendor   │
│   - Current leg progress (% of route completed)      │
│   - Known delays (customs hold, weather, etc.)       │
│                                                      │
│ Algorithm:                                           │
│                                                      │
│   remainingTime = 0                                  │
│                                                      │
│   for each remaining leg (including current):        │
│     if leg == currentLeg:                            │
│       elapsed = now - actualDeparture                │
│       avgTotal = historicalAvg(vendor, route, mode)  │
│       remainingInLeg = max(0, avgTotal - elapsed)    │
│     else:                                            │
│       remainingInLeg = historicalAvg(vendor, route,  │
│                                      mode)           │
│     end                                              │
│                                                      │
│     if leg crosses border:                           │
│       remainingInLeg += avgCustomsTime(origin,       │
│                           destination country)       │
│     end                                              │
│                                                      │
│     remainingInLeg += avgHandoffTime(leg, nextLeg)   │
│     remainingTime += remainingInLeg                  │
│                                                      │
│   ETA = now + remainingTime                          │
│   confidence = based on historical variance          │
│                                                      │
│ Output:                                              │
│   Update Shipments table: estimatedDelivery = ETA    │
│   Update Redis: eta:{trackingId}                     │
│                                                      │
│ Historical data source:                              │
│   S3 data lake with past shipment completion times   │
│   Aggregated by: vendor × route × mode × season     │
│   Refreshed daily via batch job                      │
└──────────────────────────────────────────────────────┘
```

---

## 11. Anomaly Detection

```
┌──────────────────────────────────────────────────────┐
│ Anomaly Detector (Async Worker)                      │
│                                                      │
│ Runs on: scheduled interval (every 15 min)           │
│ Also triggered by: specific event types              │
│                                                      │
│ Detection Rules:                                     │
│                                                      │
│ ┌────────────────────┬───────────────────────────┐   │
│ │ Anomaly            │ Trigger                   │   │
│ ├────────────────────┼───────────────────────────┤   │
│ │ Stuck shipment     │ No event for > 2× avg     │   │
│ │                    │ transit time for route     │   │
│ ├────────────────────┼───────────────────────────┤   │
│ │ Customs hold       │ AT_CUSTOMS for > 48 hours │   │
│ │ escalation         │                           │   │
│ ├────────────────────┼───────────────────────────┤   │
│ │ Route deviation    │ Event location doesn't    │   │
│ │                    │ match planned leg route    │   │
│ ├────────────────────┼───────────────────────────┤   │
│ │ Vendor silence     │ Active legs with vendor   │   │
│ │                    │ but no updates > 24h      │   │
│ ├────────────────────┼───────────────────────────┤   │
│ │ Delivery failure   │ FAILED_DELIVERY with no   │   │
│ │ unresolved         │ follow-up event > 12h     │   │
│ └────────────────────┴───────────────────────────┘   │
│                                                      │
│ Actions:                                             │
│   - Flag shipment in ops dashboard                   │
│   - Auto-trigger vendor polling for stuck shipments  │
│   - Escalate to ops team via internal notification   │
│   - Update ETA with delay buffer                     │
└──────────────────────────────────────────────────────┘
```

---

## 12. Trade-offs & Scaling Considerations

```
┌──────────────────────┬──────────────────────────┬──────────────────────────────┐
│ Decision             │ Chosen                   │ Trade-off                    │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ Event ingestion      │ Kafka (not direct DDB    │ + Decouples ingestion from   │
│                      │ writes)                  │   processing                 │
│                      │                          │ + Handles 50K/sec bursts     │
│                      │                          │ + Replay capability          │
│                      │                          │ - Added infra complexity     │
│                      │                          │ - ~1-2s added latency        │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ TrackingEvents SK    │ timestamp#eventId        │ + Natural chronological sort │
│                      │ (composite)              │ + Unique even if same-second │
│                      │                          │   events from vendor         │
│                      │                          │ - Slightly larger SK size    │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ Vendor ref lookup    │ GSI (VendorRefIndex)     │ + O(1) lookup for webhooks   │
│                      │ on ShipmentLegs          │ + No separate mapping table  │
│                      │                          │ - GSI storage cost           │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ Cache strategy       │ Write-through + TTL      │ + Fresh data on every update │
│                      │                          │ + Bounded staleness via TTL  │
│                      │                          │ - Redis write on every event │
│                      │                          │   (acceptable at our scale)  │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ Notification         │ Rate-limited (1 per      │ + No spam on rapid updates   │
│ throttling           │ channel per 30 min)      │ - Customer may miss          │
│                      │                          │   intermediate status        │
│                      │                          │   (acceptable — they see     │
│                      │                          │   latest on tracking page)   │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ ETA model            │ Historical averages      │ + Simple, explainable        │
│                      │ (not real-time ML)       │ + No ML infra needed         │
│                      │                          │ - Less accurate for unusual  │
│                      │                          │   routes or disruptions      │
│                      │                          │ - Can upgrade to ML later    │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ Vendor normalization │ Adapter pattern (one     │ + Clean separation per vendor│
│                      │ normalizer per vendor)   │ + Easy to onboard new vendor │
│                      │                          │ - N adapters to maintain     │
│                      │                          │ - Generic fallback for       │
│                      │                          │   long-tail vendors          │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ Data retention       │ DDB (2 years) + S3       │ + DDB for active queries     │
│                      │ (archive beyond 2y)      │ + S3 for compliance/analytics│
│                      │                          │ - Need TTL or batch job to   │
│                      │                          │   archive old records        │
├──────────────────────┼──────────────────────────┼──────────────────────────────┤
│ Idempotency          │ Redis dedup + DDB        │ + Fast dedup (Redis)         │
│                      │ conditional write         │ + Durable dedup (DDB cond.) │
│                      │                          │ - Two-layer check adds       │
│                      │                          │   slight complexity          │
└──────────────────────┴──────────────────────────┴──────────────────────────────┘
```

### Scaling Considerations

```
1. DynamoDB Auto-Scaling
   - On-Demand mode handles spiky traffic (flash sales, holiday season)
   - TrackingEvents table is the hottest — grows fastest
   - Partition key (trackingId) distributes well across partitions
   - No hot partition risk: each shipment is independent

2. Kafka Partitioning
   - Partition by trackingId → all events for one shipment go to same partition
   - Ensures ordering per shipment (critical for state machine transitions)
   - Scale consumers horizontally by adding partitions

3. Redis Cluster
   - Shard by trackingId hash
   - Read replicas for tracking query service (read-heavy)
   - Separate cluster for dedup keys (write-heavy, short-lived)

4. Event Archival
   - DDB TTL on TrackingEvents: 2 years
   - Before TTL expiry, DDB Streams → Lambda → S3 (Parquet format)
   - S3 data queryable via Athena for analytics and compliance

5. Multi-Region
   - Active-active in 2 regions for 99.95% availability
   - DynamoDB Global Tables for cross-region replication
   - Redis with cross-region replication
   - Kafka MirrorMaker for event replication
   - Route53 latency-based routing for API Gateway
```


---

## 13. End-to-End Scenario: Shanghai → New York Shipment

```
Day 0 (March 15) — Order Placed
─────────────────────────────────
  Customer in New York orders electronics from Shanghai seller.
  E-commerce platform splits into 1 shipment, 2 legs:
    Leg 1: DHL Express, AIR, Shanghai → Los Angeles
    Leg 2: USPS, ROAD, Los Angeles → New York

  createShipment() →
    Shipments:  TRK-20260315-A1B2C3, status=CREATED
    ShipmentLegs: LEG-001 (DHL, AIR, PLANNED), LEG-002 (USPS, ROAD, PLANNED)
    TrackingEvents: "Shipment created — awaiting pickup"
    NotificationSubs: customer email auto-subscribed
    Redis: tracking:{TRK-...} populated with initial state
    ETA Calculator: initial estimate = March 22

  Customer receives email: "Your order has shipped! Track it here."

Day 1 (March 16, 06:00 UTC) — Pickup
──────────────────────────────────────
  DHL picks up package from Shanghai warehouse.
  DHL webhook → POST /vendor/webhook
    vendorTrackingRef: "DHL-7890123456"
    event: "shipment.pickup"

  Ingestion Service:
    1. Auth: API key valid for VND-dhl-express ✓
    2. Dedup: Redis key doesn't exist → new event ✓
    3. VendorRefIndex lookup: DHL-7890123456 → TRK-..., LEG-001
    4. Normalize: DHL "shipment.pickup" → EventType.PICKUP

  Event Processor:
    TrackingEvents: PutItem (PICKUP event)
    ShipmentLegs: LEG-001 status → PICKED_UP
    Shipments: status → PICKED_UP, updatedAt = now
    Redis: refresh tracking:{TRK-...}
    Notification: "Your package was picked up in Shanghai"

Day 1 (March 16, 08:30 UTC) — Departure
─────────────────────────────────────────
  DHL webhook: "shipment.departure" from PVG-Air-Cargo

  Event Processor:
    TrackingEvents: PutItem (DEPARTURE event)
    ShipmentLegs: LEG-001 status → IN_TRANSIT, actualDeparture = 08:30
    Shipments: status → IN_TRANSIT
    Redis: refresh
    ETA Calculator: recalculates based on DHL AIR SH→LA historical avg (14h)
    Notification: "Your package departed Shanghai"

Day 2 (March 17, 02:00 UTC) — Arrival at LAX
──────────────────────────────────────────────
  DHL webhook: "shipment.arrival" at LAX-Cargo

  Event Processor:
    TrackingEvents: PutItem (ARRIVAL event)
    ShipmentLegs: LEG-001 status → AT_HUB
    Shipments: status stays IN_TRANSIT (still in transit overall)
    Redis: refresh
    Notification: "Your package arrived in Los Angeles"

Day 2 (March 17, 06:00 UTC) — Customs Entry
─────────────────────────────────────────────
  DHL webhook: "customs.entry" at LAX

  Event Processor:
    TrackingEvents: PutItem (CUSTOMS_ENTRY event)
    ShipmentLegs: LEG-001 status → AT_CUSTOMS, customsClearance → IN_REVIEW
    Shipments: status → AT_CUSTOMS
    Redis: refresh
    Notification: "Your package is being processed by US Customs"

Day 2 (March 17, 14:00 UTC) — Customs Cleared
───────────────────────────────────────────────
  DHL webhook: "customs.cleared", duty: $8.50

  Event Processor:
    TrackingEvents: PutItem (CUSTOMS_CLEARED event)
    ShipmentLegs: LEG-001 customsClearance → CLEARED,
                  customsDetails = "Cleared at LAX, duty $8.50"
    Shipments: status → CUSTOMS_CLEARED
    Redis: refresh
    ETA Calculator: no customs delay — ETA unchanged
    Notification: "Your package cleared customs"

Day 3 (March 18, 10:00 UTC) — Leg Handoff (DHL → USPS)
────────────────────────────────────────────────────────
  DHL webhook: "shipment.delivered" to LAX-Transfer-Facility

  Event Processor (leg handoff logic):
    ShipmentLegs: LEG-001 status → DELIVERED_TO_NEXT, actualArrival = 10:00
    ShipmentLegs: LEG-002 (no change yet — waiting for USPS pickup)
    Shipments: currentLegId → LEG-002, status → IN_TRANSIT
    TrackingEvents: PutItem ("Transferred to local carrier in Los Angeles")
    Redis: refresh
    Async: Notify USPS via API — "Package ready for pickup at LAX facility"

Day 3 (March 18, 16:00 UTC) — USPS Pickup
───────────────────────────────────────────
  USPS webhook: "package.accepted"

  Event Processor:
    TrackingEvents: PutItem (PICKUP event, leg 2)
    ShipmentLegs: LEG-002 status → PICKED_UP, actualDeparture = 16:00
    Shipments: status stays IN_TRANSIT
    Redis: refresh
    ETA Calculator: USPS ROAD LA→NY historical avg = 4 days → ETA = March 22
    Notification: "Your package is on its way from Los Angeles"

Day 5 (March 20, 12:00 UTC) — In Transit Update
─────────────────────────────────────────────────
  USPS webhook: "package.in_transit" at Denver hub

  Event Processor:
    TrackingEvents: PutItem (IN_TRANSIT event)
    ShipmentLegs: LEG-002 status stays IN_TRANSIT
    Redis: refresh
    Notification: suppressed (rate limit — last notification was < 30 min ago?
                  No, last was 2 days ago → send it)
    Notification: "Your package is in transit — currently in Denver, CO"

Day 7 (March 22, 08:00 UTC) — Out for Delivery
────────────────────────────────────────────────
  USPS webhook: "package.out_for_delivery" in New York

  Event Processor:
    TrackingEvents: PutItem (OUT_FOR_DELIVERY event)
    ShipmentLegs: LEG-002 status → (no specific leg status — still IN_TRANSIT)
    Shipments: status → OUT_FOR_DELIVERY
    Redis: refresh with TTL = 5 min (short TTL for last-mile)
    Notification: "Your package is out for delivery today!"

Day 7 (March 22, 14:30 UTC) — Delivered
────────────────────────────────────────
  USPS webhook: "package.delivered", signed by "J. Smith"

  Event Processor:
    TrackingEvents: PutItem (DELIVERED event)
    ShipmentLegs: LEG-002 status → COMPLETED, actualArrival = 14:30
    Shipments: status → DELIVERED, actualDelivery = 14:30
    Redis: refresh (TTL back to 1 hour — no more frequent refreshes needed)
    Notification: "Your package has been delivered! Signed by J. Smith"

  Total journey: 7 days, 2 legs, 2 vendors, 1 customs clearance
  Events recorded: 11 tracking events in TrackingEvents table
  Vendor webhooks processed: 9 (DHL: 5, USPS: 4)
  Notifications sent: 9 (email)
  ETA recalculations: 4
  Cache hits during customer tracking page views: ~87%
```

### Component Responsibilities

```
┌──────────────────┬──────────────────────────────────────────────────────┐
│ Component        │ Responsibility                                       │
├──────────────────┼──────────────────────────────────────────────────────┤
│ API Gateway      │ TLS, rate limiting, auth (user JWT + vendor API key)│
│ Tracking Query   │ Read-optimized: fetch current status, event history │
│ Service          │ Serves customer-facing tracking page                │
│ Ingestion Service│ Receives vendor webhook pushes, normalizes formats, │
│                  │ validates, publishes to event bus                   │
│ Shipment Mgmt    │ CRUD for shipments, legs, route planning            │
│ DynamoDB         │ Source of truth: shipments, tracking events, legs   │
│ Redis            │ Hot cache: latest status per trackingId (< 10ms)   │
│ Kafka / SQS      │ Event bus: decouple ingestion from processing      │
│ S3               │ Archived events (> 90 days), analytics data lake   │
│ Notification Svc │ Push/email/SMS when status changes                  │
│ ETA Calculator   │ Predicts delivery date from leg data + history     │
│ Anomaly Detector │ Flags stuck/delayed/rerouted shipments              │
└──────────────────┴──────────────────────────────────────────────────────┘
```

---

## 3. Core Data Model

### 3.1 Entity Relationships

```
┌──────────┐ 1:N  ┌──────────────┐ 1:N  ┌──────────────┐
│  Order   │─────▶│  Shipment    │─────▶│  ShipmentLeg │
└──────────┘      └──────┬───────┘      └──────┬───────┘
                         │ 1:N                  │ 1:N
                         ▼                      ▼
                  ┌──────────────┐      ┌──────────────┐
                  │ TrackingEvent│      │ LegEvent     │
                  │ (shipment-   │      │ (leg-level   │
                  │  level)      │      │  milestones) │
                  └──────────────┘      └──────────────┘

┌──────────────┐
│   Vendor     │ ← each leg is handled by one vendor
└──────────────┘
```

### 3.2 Entity Definitions

```
┌──────────────────────────┐
│       Shipment            │
│──────────────────────────│
│ - trackingId: String      │  e.g. "TRK-20260326-ABC123"
│ - orderId: String         │  parent e-commerce order
│ - origin: Location        │  {country, city, facility}
│ - destination: Location   │
│ - currentStatus: Status   │  latest rollup status
│ - currentLocation: String │  last known location
│ - legs: List<LegId>       │  ordered sequence of legs
│ - activeLegIndex: int     │  which leg is currently active
│ - estimatedDelivery: Date │
│ - actualDelivery: Date    │
│ - createdAt: Instant      │
│ - updatedAt: Instant      │
└──────────────────────────┘

┌──────────────────────────┐
│      ShipmentLeg          │
│──────────────────────────│
│ - legId: String           │  e.g. "LEG-001"
│ - trackingId: String      │  parent shipment
│ - legIndex: int           │  sequence order (0, 1, 2...)
│ - vendorId: String        │  which 3P handles this leg
│ - vendorTrackingRef: String│ vendor's own tracking number
│ - transportMode: Mode     │  AIR | SEA | ROAD | RAIL
│ - originFacility: String  │  e.g. "Shanghai Port"
│ - destFacility: String    │  e.g. "LA Port"
│ - originCountry: String   │
│ - destCountry: String     │
│ - status: LegStatus       │
│ - estimatedDeparture: Date│
│ - actualDeparture: Date   │
│ - estimatedArrival: Date  │
│ - actualArrival: Date     │
│ - isCrossCountry: boolean │
│ - customsClearance: Status│  PENDING | CLEARED | HELD
└──────────────────────────┘

┌──────────────────────────┐
│     TrackingEvent         │
│──────────────────────────│
│ - eventId: String         │
│ - trackingId: String      │
│ - legId: String           │  which leg this event belongs to
│ - timestamp: Instant      │  when it happened (UTC)
│ - status: EventStatus     │
│ - location: String        │  e.g. "Shanghai Pudong Hub"
│ - country: String         │
│ - city: String            │
│ - description: String     │  human-readable description
│ - vendorId: String        │  which vendor reported this
│ - vendorRawPayload: String│  original vendor payload (for debugging)
│ - isCustomerVisible: bool │  some events are internal-only
└──────────────────────────┘

┌──────────────────────────┐
│        Vendor             │
│──────────────────────────│
│ - vendorId: String        │  e.g. "VND-fedex"
│ - name: String            │  "FedEx International"
│ - apiKeyHash: String      │
│ - webhookFormat: String   │  JSON schema version
│ - supportedModes: List    │  [AIR, ROAD]
│ - supportedCountries: List│
│ - status: VendorStatus    │  ACTIVE | SUSPENDED
│ - slaHours: int           │  expected update frequency
└──────────────────────────┘
```

---

## 4. Enums & State Machines

```java
public enum TransportMode     { AIR, SEA, ROAD, RAIL }
public enum ShipmentStatus    { CREATED, PICKED_UP, IN_TRANSIT, AT_CUSTOMS,
                                CUSTOMS_CLEARED, OUT_FOR_DELIVERY, DELIVERED,
                                FAILED, RETURNED }
public enum LegStatus         { PENDING, IN_TRANSIT, ARRIVED, COMPLETED, FAILED }
public enum CustomsStatus     { NOT_APPLICABLE, PENDING, CLEARED, HELD, REJECTED }
public enum EventStatus       { ORDER_PLACED, PICKED_UP, DEPARTED_FACILITY,
                                IN_TRANSIT, ARRIVED_AT_HUB, CUSTOMS_CHECK,
                                CUSTOMS_CLEARED, CUSTOMS_HELD,
                                OUT_FOR_DELIVERY, DELIVERED,
                                DELIVERY_FAILED, RETURNED_TO_SENDER }
```

### Shipment Status State Machine

```
┌─────────┐     ┌───────────┐     ┌────────────┐     ┌─────────────┐
│ CREATED │────▶│ PICKED_UP │────▶│ IN_TRANSIT │────▶│ AT_CUSTOMS  │
└─────────┘     └───────────┘     └────────────┘     └──────┬──────┘
                                        ▲                    │
                                        │              ┌─────┴──────┐
                                        │              ▼            ▼
                                        │     ┌──────────────┐  ┌──────┐
                                        └─────│CUSTOMS_CLEARED│  │ HELD │
                                              └──────┬───────┘  └──┬───┘
                                                     │             │
                                                     ▼             ▼
                                          ┌──────────────────┐  ┌──────────┐
                                          │ OUT_FOR_DELIVERY │  │ RETURNED │
                                          └────────┬─────────┘  └──────────┘
                                                   │
                                            ┌──────┴──────┐
                                            ▼             ▼
                                      ┌───────────┐  ┌────────┐
                                      │ DELIVERED │  │ FAILED │
                                      └───────────┘  └────────┘
```

### Cross-Border Shipment Lifecycle (Multi-Leg)

```
Leg 0: Origin Country (Road)          Leg 1: International (Air/Sea)
┌─────────────────────────────┐      ┌─────────────────────────────┐
│ Seller warehouse → Origin   │      │ Origin port/airport →       │
│ port/airport                │─────▶│ Destination port/airport    │
│ Vendor: Local courier       │      │ Vendor: Freight carrier     │
│ Mode: ROAD                  │      │ Mode: AIR or SEA            │
└─────────────────────────────┘      └──────────────┬──────────────┘
                                                     │
Leg 3: Last Mile (Road)              Leg 2: Dest Country (Road)
┌─────────────────────────────┐      ┌─────────────────────────────┐
│ Local hub → Customer        │◀─────│ Dest port → Customs →       │
│ doorstep                    │      │ Local distribution hub      │
│ Vendor: Local delivery      │      │ Vendor: Customs broker +    │
│ Mode: ROAD                  │      │ local freight               │
└─────────────────────────────┘      │ Mode: ROAD                  │
                                     │ ** CUSTOMS CLEARANCE HERE ** │
                                     └─────────────────────────────┘
```

---

## 5. DynamoDB Table Design

### 5.1 Shipments Table (On-Demand)

```
Table Name: Shipments

PK: trackingId (S)          e.g. "TRK-20260326-ABC123"

Attributes:
  orderId           (S)     "ORD-789"
  origin            (M)     {country: "CN", city: "Shanghai", facility: "PVG Warehouse"}
  destination       (M)     {country: "US", city: "Seattle", facility: "Customer addr"}
  currentStatus     (S)     "IN_TRANSIT"
  currentLocation   (S)     "Los Angeles Port"
  currentCountry    (S)     "US"
  activeLegIndex    (N)     2
  totalLegs         (N)     4
  estimatedDelivery (S)     ISO-8601
  actualDelivery    (S)     ISO-8601 (null until delivered)
  createdAt         (S)     ISO-8601
  updatedAt         (S)     ISO-8601

GSI: OrderShipmentsIndex
  PK: orderId (S)
  SK: createdAt (S)
  → "Show me all packages for order ORD-789"

GSI: StatusIndex
  PK: currentStatus (S)
  SK: updatedAt (S)
  → Ops dashboard: all shipments stuck AT_CUSTOMS
```

### 5.2 ShipmentLegs Table (On-Demand)

```
Table Name: ShipmentLegs

PK: trackingId (S)          e.g. "TRK-20260326-ABC123"
SK: legIndex (N)            e.g. 0, 1, 2, 3

Attributes:
  legId             (S)     "LEG-001"
  vendorId          (S)     "VND-fedex"
  vendorTrackingRef (S)     "FX-9876543210"
  transportMode     (S)     "AIR"
  originFacility    (S)     "Shanghai Pudong Airport"
  destFacility      (S)     "LAX Cargo Terminal"
  originCountry     (S)     "CN"
  destCountry       (S)     "US"
  status            (S)     "COMPLETED"
  customsClearance  (S)     "NOT_APPLICABLE"
  estimatedDeparture (S)    ISO-8601
  actualDeparture   (S)     ISO-8601
  estimatedArrival  (S)     ISO-8601
  actualArrival     (S)     ISO-8601
  isCrossCountry    (BOOL)  true

GSI: VendorLegsIndex
  PK: vendorId (S)
  SK: trackingId (S)
  → Vendor dashboard: all legs handled by FedEx

→ Query all legs for a shipment: PK=trackingId, SK between 0 and 99
→ Get specific leg: PK=trackingId, SK=legIndex
```

### 5.3 TrackingEvents Table (On-Demand)

```
Table Name: TrackingEvents

PK: trackingId (S)          e.g. "TRK-20260326-ABC123"
SK: timestamp#eventId (S)   e.g. "2026-03-20T14:30:00Z#EVT-abc123"

Attributes:
  eventId           (S)     "EVT-abc123"
  legId             (S)     "LEG-002"
  legIndex          (N)     2
  status            (S)     "ARRIVED_AT_HUB"
  location          (S)     "Los Angeles Port"
  country           (S)     "US"
  city              (S)     "Los Angeles"
  description       (S)     "Package arrived at LA port facility"
  vendorId          (S)     "VND-maersk"
  vendorRawPayload  (S)     JSON string (original vendor data)
  isCustomerVisible (BOOL)  true
  transportMode     (S)     "SEA"

→ Query full history: PK=trackingId, SK sorted ascending = chronological timeline
→ Latest event: PK=trackingId, SK descending, limit 1

GSI: LegEventsIndex
  PK: trackingId#legId (S)  e.g. "TRK-ABC123#LEG-002"
  SK: timestamp (S)
  → Events for a specific leg only
```

### 5.4 Vendors Table (On-Demand)

```
Table Name: Vendors

PK: vendorId (S)            e.g. "VND-fedex"

Attributes:
  name              (S)     "FedEx International"
  apiKeyHash        (S)     bcrypt hash
  webhookFormat     (S)     "v2"
  supportedModes    (SS)    {"AIR", "ROAD"}
  supportedCountries (SS)   {"US", "CN", "DE", "JP", ...}
  status            (S)     "ACTIVE"
  slaHours          (N)     6  (expected update frequency)
  webhookUrl        (S)     for polling fallback
  contactEmail      (S)     "[email]"
```

### 5.5 NotificationSubscriptions Table (On-Demand, TTL enabled)

```
Table Name: NotificationSubscriptions

PK: trackingId (S)          e.g. "TRK-20260326-ABC123"
SK: channel#userId (S)      e.g. "PUSH#usr-42" or "EMAIL#usr-42"

Attributes:
  userId            (S)     "usr-42"
  channel           (S)     "PUSH" | "EMAIL" | "SMS"
  endpoint          (S)     device token / email / phone
  subscribedAt      (S)     ISO-8601
  ttl               (N)     Unix epoch — auto-delete 30 days after delivery

→ Query all subscribers for a shipment: PK=trackingId
```

### DynamoDB Table Summary

```
┌───────────────────────────┬──────────────┬──────────────────┬──────────────────────────┐
│ Table                     │ PK           │ SK               │ GSIs                     │
├───────────────────────────┼──────────────┼──────────────────┼──────────────────────────┤
│ Shipments                 │ trackingId   │ —                │ OrderShipmentsIndex,     │
│                           │              │                  │ StatusIndex              │
│ ShipmentLegs              │ trackingId   │ legIndex         │ VendorLegsIndex          │
│ TrackingEvents            │ trackingId   │ timestamp#evtId  │ LegEventsIndex           │
│ Vendors                   │ vendorId     │ —                │ —                        │
│ NotificationSubscriptions │ trackingId   │ channel#userId   │ —                        │
└───────────────────────────┴──────────────┴──────────────────┴──────────────────────────┘

All tables On-Demand. NotificationSubscriptions has TTL for auto-cleanup.
TrackingEvents archived to S3 after 90 days via DDB Streams → Lambda.
```

---

## 6. Flow Diagrams

### Flow 1: Vendor Pushes a Status Update (Write Path)

```
3P Vendor sends webhook
     │
     ▼
┌──────────────────────────┐
│ API Gateway              │
│                          │
│ 1. Validate vendor API   │
│    key (Vendors table)   │
│ 2. Rate limit per vendor │
│ 3. Route to Ingestion    │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Ingestion Service        │
│                          │
│ 1. Parse vendor payload  │
│    (each vendor has      │
│    different format)     │
│                          │
│ 2. Normalize to internal │
│    TrackingEvent schema: │
│    - Map vendor status   │
│      codes → our enums   │
│    - Extract location,   │
│      timestamp, etc.     │
│                          │
│ 3. Validate:             │
│    - trackingId exists   │
│    - vendorId matches    │
│      the leg's vendor    │
│    - timestamp not in    │
│      future              │
│    - Idempotency check   │
│      (dedup by eventId)  │
│                          │
│ 4. Publish to Kafka/SQS  │
│    topic: tracking-events│
│                          │
│ 5. Return 202 Accepted   │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Event Processing Worker  │
│ (consumes from Kafka)    │
│                          │
│ 1. Write TrackingEvent   │
│    to DDB                │
│                          │
│ 2. Update ShipmentLeg    │
│    status if milestone   │
│    (e.g., ARRIVED)       │
│                          │
│ 3. Update Shipment:      │
│    - currentStatus       │
│    - currentLocation     │
│    - activeLegIndex      │
│      (if leg completed,  │
│       advance to next)   │
│                          │
│ 4. Update Redis cache:   │
│    SET tracking:{id}     │
│    {status, location,    │
│     updatedAt, ETA}      │
│                          │
│ 5. If status changed:    │
│    → publish to          │
│      notification-events │
│      topic               │
│                          │
│ 6. If leg completed:     │
│    → trigger ETA         │
│      recalculation       │
└──────────────────────────┘
```

### Flow 2: Customer Checks Tracking Status (Read Path)

```
Customer opens tracking page
     │
     ▼
┌──────────────────────────┐
│ Tracking Query Service   │
│                          │
│ 1. Check Redis cache:    │
│    GET tracking:{id}     │
│                          │
│    Cache HIT (< 1ms):   │
│    → Return cached       │
│      {status, location,  │
│       ETA, lastUpdate}   │
│                          │
│    Cache MISS:           │
│    → Query DDB Shipments │
│      table by trackingId │
│    → Populate Redis      │
│      (TTL = 60 seconds)  │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ If user requests full    │
│ history (timeline view): │
│                          │
│ Query TrackingEvents:    │
│   PK = trackingId        │
│   SK ascending           │
│   Filter: isCustomer-    │
│     Visible = true       │
│                          │
│ Query ShipmentLegs:      │
│   PK = trackingId        │
│   All legs (SK 0..N)     │
│                          │
│ Merge into timeline:     │
│ ┌────────────────────┐   │
│ │ Mar 20 - Shanghai  │   │
│ │ Picked up by       │   │
│ │ local courier      │   │
│ │ (Leg 0: ROAD)      │   │
│ ├────────────────────┤   │
│ │ Mar 21 - Shanghai  │   │
│ │ Arrived at airport │   │
│ │ (Leg 0 → Leg 1)   │   │
│ ├────────────────────┤   │
│ │ Mar 22 - In flight │   │
│ │ Shanghai → LA      │   │
│ │ (Leg 1: AIR)       │   │
│ ├────────────────────┤   │
│ │ Mar 23 - LA Port   │   │
│ │ Customs clearance  │   │
│ │ (Leg 2: CUSTOMS)   │   │
│ ├────────────────────┤   │
│ │ Mar 24 - Seattle   │   │
│ │ Out for delivery   │   │
│ │ (Leg 3: ROAD)      │   │
│ └────────────────────┘   │
└──────────────────────────┘
```

### Flow 3: Shipment Creation (E-Commerce Platform)

```
E-commerce platform creates shipment after order placed
     │
     ▼
┌──────────────────────────┐
│ Shipment Management Svc  │
│                          │
│ createShipment(          │
│   orderId,               │
│   origin: {CN, Shanghai},│
│   dest: {US, Seattle},   │
│   legs: [                │
│     {vendor: local-cn,   │
│      mode: ROAD,         │
│      from: warehouse,    │
│      to: PVG airport},   │
│     {vendor: fedex,      │
│      mode: AIR,          │
│      from: PVG,          │
│      to: SEA airport},   │
│     {vendor: customs-us, │
│      mode: ROAD,         │
│      from: SEA airport,  │
│      to: SEA hub},       │
│     {vendor: local-us,   │
│      mode: ROAD,         │
│      from: SEA hub,      │
│      to: customer addr}  │
│   ]                      │
│ )                        │
│                          │
│ 1. Generate trackingId   │
│ 2. Write Shipment to DDB │
│    status = CREATED      │
│ 3. Write each leg to     │
│    ShipmentLegs table    │
│ 4. Write initial event:  │
│    "Shipment created"    │
│ 5. Notify each vendor    │
│    of their leg details  │
│ 6. Return trackingId     │
│    to e-commerce platform│
└──────────────────────────┘
```

### Flow 4: Vendor Normalization (Multi-Format Ingestion)

```
Different vendors send different formats:

FedEx (JSON):                    DHL (XML):
{                                <shipment-update>
  "tracking_number": "FX-123",     <tracking>DHL-456</tracking>
  "event": "DEPARTURE_SCAN",       <event-code>DF</event-code>
  "location": {                    <location>
    "city": "Shanghai",              <city>Frankfurt</city>
    "country": "CN"                  <country>DE</country>
  },                               </location>
  "timestamp": "2026-03-20T..."    <timestamp>2026-03-20T...</timestamp>
}                                </shipment-update>

Maersk (webhook):                Local courier (polling):
{                                GET /api/track?ref=LC-789
  "container_id": "MSKU123",    Response:
  "port_event": "VESSEL_DEPART",  { "status": "delivered",
  "port": "CNSHA",                  "time": "1710936000" }
  "vessel": "Ever Given",
  "eta_dest": "2026-04-01"
}

                    │
                    ▼
┌──────────────────────────────────────────────────┐
│           Vendor Adapter Layer                    │
│                                                   │
│  Each vendor has a VendorAdapter implementation:  │
│                                                   │
│  interface VendorAdapter {                        │
│    TrackingEvent normalize(String rawPayload);    │
│    String getVendorId();                          │
│  }                                                │
│                                                   │
│  FedExAdapter:                                    │
│    "DEPARTURE_SCAN" → DEPARTED_FACILITY           │
│    location.city → city                           │
│                                                   │
│  DhlAdapter:                                      │
│    "DF" → DEPARTED_FACILITY                       │
│    parse XML → extract fields                     │
│                                                   │
│  MaerskAdapter:                                   │
│    "VESSEL_DEPART" → IN_TRANSIT                   │
│    port code "CNSHA" → "Shanghai, CN"             │
│                                                   │
│  All adapters produce the same TrackingEvent      │
│  schema → downstream processing is vendor-agnostic│
└──────────────────────────────────────────────────┘
```

---

## 7. DDB Write Triggers — Who Writes What & When

```
┌──────────────────────┬───────────────┬───────────────────┬───────────────────────────┐
│ Action               │ Triggered By  │ Tables Written    │ Write Type               │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ createShipment       │ E-Commerce    │ Shipments         │ PutItem                  │
│                      │ Platform      │ ShipmentLegs      │ PutItem per leg          │
│                      │               │ TrackingEvents    │ PutItem (initial event)  │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ ingestVendorUpdate   │ 3P Vendor     │ TrackingEvents    │ PutItem (new event)      │
│ (via event worker)   │ (webhook)     │ ShipmentLegs      │ UpdateItem (leg status)  │
│                      │               │ Shipments         │ UpdateItem (current      │
│                      │               │                   │   status, location)      │
│                      │               │ Redis             │ SET (cache refresh)      │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ subscribeNotification│ Customer      │ NotificationSubs  │ PutItem                  │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ markDelivered        │ Last-mile     │ Shipments         │ UpdateItem (status=      │
│                      │ vendor        │                   │   DELIVERED, actualDlvry)│
│                      │               │ TrackingEvents    │ PutItem (DELIVERED event) │
│                      │               │ Redis             │ SET + short TTL          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ customsClearance     │ Customs       │ ShipmentLegs      │ UpdateItem (customs=     │
│                      │ broker vendor │                   │   CLEARED or HELD)       │
│                      │               │ Shipments         │ UpdateItem (status)      │
│                      │               │ TrackingEvents    │ PutItem                  │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ recalculateETA       │ ETA Worker    │ Shipments         │ UpdateItem               │
│                      │ (async)       │                   │   (estimatedDelivery)    │
│                      │               │ Redis             │ SET (updated ETA)        │
└──────────────────────┴───────────────┴───────────────────┴───────────────────────────┘
```

---

## 8. Vendor Integration Patterns

### Webhook Push (Primary — 80% of vendors)

```
Vendor → POST /api/v1/vendors/{vendorId}/events
Headers: X-API-Key: <vendor-api-key>
Body: vendor-specific payload

Response: 202 Accepted
  { "eventId": "EVT-abc123", "status": "queued" }

Retry policy (vendor side):
  If non-2xx → retry with exponential backoff
  3 retries over 15 minutes
```

### Polling Fallback (20% of vendors — legacy systems)

```
┌──────────────────────────┐
│ Polling Scheduler        │
│ (runs every 5 minutes)   │
│                          │
│ For each active leg with │
│ a polling-based vendor:  │
│                          │
│ 1. Call vendor API:      │
│    GET /track?ref={ref}  │
│                          │
│ 2. Compare with last     │
│    known status          │
│                          │
│ 3. If changed:           │
│    → Normalize + publish │
│      to same Kafka topic │
│    → Same downstream     │
│      processing as       │
│      webhook path        │
│                          │
│ 4. If unchanged:         │
│    → No-op               │
└──────────────────────────┘
```

### Vendor Health Monitoring

```
┌──────────────────────────────────────────────────────────┐
│ Anomaly Detector checks:                                  │
│                                                          │
│ 1. Vendor SLA breach:                                    │
│    If no update from vendor for > slaHours               │
│    → Alert ops team                                      │
│    → Switch to polling if webhook-based                  │
│                                                          │
│ 2. Stuck shipments:                                      │
│    If shipment status unchanged for > 48 hours           │
│    → Flag for manual investigation                       │
│                                                          │
│ 3. Customs delays:                                       │
│    If AT_CUSTOMS for > 72 hours                          │
│    → Notify customer: "Customs processing delay"         │
│    → Alert ops for intervention                          │
│                                                          │
│ 4. Route deviation:                                      │
│    If event location doesn't match expected leg route    │
│    → Flag for review (possible misroute)                 │
└──────────────────────────────────────────────────────────┘
```

---

## 9. Caching Strategy

```
┌──────────────────────────────────────────────────────────┐
│                  Redis Cache Layer                         │
├──────────────────┬───────────────────────────────────────┤
│ Key              │ Value & TTL                            │
├──────────────────┼───────────────────────────────────────┤
│ tracking:{id}    │ {status, location, country, ETA,      │
│                  │  lastUpdate, activeLeg, totalLegs}    │
│                  │ TTL: 60 seconds                       │
│                  │ Refreshed on every vendor update      │
│                  │ → Serves 200K+ reads/sec              │
├──────────────────┼───────────────────────────────────────┤
│ vendor:{id}      │ {name, status, webhookFormat}         │
│                  │ TTL: 5 minutes                        │
│                  │ → Ingestion service validates vendor  │
├──────────────────┼───────────────────────────────────────┤
│ timeline:{id}    │ Serialized event list (last 20 events)│
│                  │ TTL: 30 seconds                       │
│                  │ → Avoids DDB query for timeline view  │
└──────────────────┴───────────────────────────────────────┘

Cache invalidation:
  Event Processing Worker updates Redis on every new event.
  No stale-read risk — cache is write-through on the event path.
```

---

## 10. Scaling & Trade-offs

```
┌──────────────────────────┬──────────────────────┬──────────────────────────┐
│ Decision                 │ Chose                │ Trade-off                │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Event ingestion          │ Async (Kafka/SQS)    │ + Decouples vendors from │
│                          │                      │   processing             │
│                          │                      │ + Handles burst traffic  │
│                          │                      │ - 1-5 sec delay before   │
│                          │                      │   event visible to user  │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Status query             │ Redis cache + DDB    │ + < 10ms for cached      │
│                          │ fallback             │ + 200K+ QPS on reads     │
│                          │                      │ - 60 sec stale window    │
│                          │                      │   (acceptable for        │
│                          │                      │   shipment tracking)     │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Event storage            │ DDB (hot) + S3 (cold)│ + DDB fast for recent    │
│                          │                      │ + S3 cheap for archive   │
│                          │                      │ - Need DDB Streams +     │
│                          │                      │   Lambda for archival    │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Vendor integration       │ Adapter pattern      │ + Each vendor isolated   │
│                          │ (per-vendor adapter) │ + Easy to add new vendor │
│                          │                      │ - N adapters to maintain │
│                          │                      │ - Vendor format changes  │
│                          │                      │   require adapter update │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Multi-leg tracking       │ Separate Legs table  │ + Clean per-leg status   │
│                          │ with leg-level events│ + Vendor isolation       │
│                          │                      │ - More complex rollup    │
│                          │                      │   logic for shipment     │
│                          │                      │   status                 │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Notifications            │ Async via event bus  │ + No latency on write    │
│                          │                      │   path                   │
│                          │                      │ - Notification may lag   │
│                          │                      │   by seconds             │
└──────────────────────────┴──────────────────────┴──────────────────────────┘
```

---

## 11. End-to-End Scenario

```
Day 0: Customer orders a laptop from Shanghai → Seattle

  E-commerce platform calls createShipment:
    → trackingId = TRK-20260326-ABC123
    → 4 legs planned: local pickup → air freight → customs → last mile
    → Shipments: PutItem (CREATED)
    → ShipmentLegs: 4 PutItems
    → Customer gets tracking link

Day 1: Local courier picks up from seller warehouse
  → Vendor "local-cn" pushes webhook: PICKED_UP at Shanghai warehouse
  → Ingestion → Kafka → Worker:
    - TrackingEvents: PutItem
    - Shipments: status=PICKED_UP, location="Shanghai Warehouse"
    - Redis: updated
    - Notification: "Your package has been picked up"

Day 2: Package arrives at Shanghai Pudong Airport
  → Vendor "local-cn" pushes: ARRIVED_AT_HUB at PVG
  → Leg 0 marked COMPLETED, activeLegIndex → 1
  → Vendor "fedex" notified: your leg is starting

Day 2: FedEx departs Shanghai
  → FedEx pushes: DEPARTED_FACILITY at PVG
  → Shipment: IN_TRANSIT, location="Shanghai → Los Angeles (in flight)"

Day 3: FedEx arrives LA
  → FedEx pushes: ARRIVED_AT_HUB at LAX Cargo
  → Leg 1 COMPLETED, activeLegIndex → 2
  → Shipment: AT_CUSTOMS

Day 4: Customs clearance
  → Customs broker pushes: CUSTOMS_CLEARED
  → Leg 2 customs = CLEARED
  → Shipment: CUSTOMS_CLEARED
  → Notification: "Your package cleared customs"

Day 5: Last mile delivery
  → Local US courier pushes: OUT_FOR_DELIVERY
  → Notification: "Out for delivery today"
  → Later: DELIVERED at customer address
  → Shipment: DELIVERED, actualDelivery = now
  → Notification: "Your laptop has been delivered"
  → NotificationSubscriptions TTL starts (30 day cleanup)
```
