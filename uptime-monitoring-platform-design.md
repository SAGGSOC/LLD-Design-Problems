# Uptime Monitoring Platform — System Design Document

---

## 1. Functional Requirements

- `registerMonitor(userId, url, checkInterval, alertChannels[]) → monitorId` — register a website/API for periodic health checks
- `executeCheck(monitorId) → {status, responseTime, statusCode}` — perform HTTP(S) check from multiple regions
- `getMonitorStatus(monitorId) → {currentStatus, uptime30d, avgResponseTime, lastChecked}` — current health + historical uptime
- `getIncidentHistory(monitorId, timeRange) → incidents[]` — downtime incidents with start/end, duration, cause
- `triggerAlert(monitorId, incident) → {notifiedChannels[], timestamp}` — notify via email, Slack, webhook, SMS on state change
- `getUptimeReport(monitorId, period) → {uptimePercent, avgLatency, p95Latency, incidents, checkCount}` — uptime/perf report

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| Protocols supported? | HTTP, HTTPS. TCP/ping out of scope v1. |
| Monitors per user? | Free: 5, Pro: 50, Enterprise: 500 |
| Min check interval? | Free: 5min, Pro: 1min, Enterprise: 15sec |
| Check regions? | 6 global (US-East, US-West, EU-West, EU-Central, AP-South, AP-East) |
| What is "down"? | Non-2xx, timeout >10s, or SSL error. Configurable per monitor. |
| Alert channels? | Email, Slack, webhook, SMS. PagerDuty for Enterprise. |
| Data retention? | Raw checks: 90d. Aggregated metrics: 2yr. Incidents: forever. |

### Assumptions

- 100K registered users, 10K DAU
- 500K total monitors, 200K active
- Avg check interval 2min → 200K/120 = 1,667 checks/sec steady, peak 3× = 5,000/sec
- Each check fans out to 3 regions → 15K HTTP reqs/sec peak
- 95% of checks healthy, 5% monitors down at any time (~10K active incidents)
- Alert burst: ~500 alerts/min during widespread outage

---

## 2. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Scale | 500K monitors, 200K active, 15K HTTP checks/sec peak |
| Throughput | 5K check results ingested/sec; 500 alerts/min burst |
| Latency | Scheduling jitter < 5% of interval; alert delivery < 30s |
| Read/write ratio | 10:1 (dashboards dominate reads; checks are writes) |
| Consistency | Eventual for metrics; strong for monitor config + billing |
| Availability | 99.99% for check execution |
| Durability | Zero incident loss; checks durable within 5s |
| Constraints | Multi-region checks; alert dedup; idempotent execution |

### Back-of-Envelope

- Storage: 200K × 720 checks/day × 500B = 72 GB/day raw. 90d retention = ~6.5 TB
- Rollups: 200K × 1,440 pts/day × 50B = 14.4 GB/day → 2yr = ~10.5 TB
- In-flight: 5K checks/sec × 2s avg duration = 10K concurrent, ~3.3K/region
- Alerts: 10K incidents × 2 channels = 20K deliveries per wave, burst 8.3/sec

---

## 3. Core Entities

**User:**
```
userId (PK) | email   | plan          | api_key_hash | created_at          | monitors_limit
"U-10421"   | [email] | "pro"         | sha256:...   | 2025-06-15T10:00:00 | 50
```

**Monitor:**
```
monitor_id (PK) | user_id | name             | url                            | method | interval_sec | timeout_sec | regions[]         | expected_status | headers_json | body_pattern | ssl_check | status
"MON-88201"     | U-10421 | "Production API" | https://api.example.com/health | GET    | 60           | 10          | [us-east,eu-west] | 200             | {"Auth":...} | "ok"         | true      | active
```

**CheckResult:**
```
check_id (PK)  | monitor_id | region  | status_code | response_time_ms | is_up | error_message | checked_at          | ssl_expiry_days
"CHK-9920134"  | MON-88201  | us-east | 200         | 142              | true  | null          | 2026-03-14T08:30:00 | 45
```

**Incident:**
```
incident_id (PK) | monitor_id | started_at          | resolved_at         | duration_sec | cause   | regions_affected[] | checks_failed
"INC-4410"       | MON-88201  | 2026-03-13T14:22:00 | 2026-03-13T14:38:00 | 960          | timeout | [us-east,eu-west]  | 16
```

**AlertChannel:**
```
channel_id (PK) | user_id | type  | config_json                        | verified
"ALC-301"       | U-10421 | slack | {"webhook_url":"https://hooks..."} | true
```

**MonitorAlertBinding:**
```
monitor_id (PK) | channel_id (SK) | notify_on      | cooldown_min | escalation_after_min
MON-88201       | ALC-301         | down,recovered | 5            | 30
```

**MetricRollup (TimescaleDB):**
```
monitor_id | bucket             | granularity | avg_response_ms | p95_response_ms | check_count | up_count | down_count
MON-88201  | 2026-03-14T08:00   | 1h          | 148             | 312             | 60          | 59       | 1
```

### Entity Relationships
```
User ──1:N──▶ Monitors ──1:N──▶ CheckResults
                       ──1:N──▶ Incidents
                       ──N:N──▶ AlertChannels (via MonitorAlertBinding)
User ──1:N──▶ AlertChannels
MetricRollup aggregates CheckResults per monitor per time bucket
```

### Hot-Path vs Cold-Path

| Entity | Hot Path (Redis) | Cold Path (PostgreSQL / TimescaleDB) |
|---|---|---|
| Schedule | ZSET: score = next_check_epoch, member = monitor_id | Monitor table (source of truth) |
| Latest Status | HSET monitor_status:{id} → is_up, last_check_at, response_ms | Derived from latest CheckResult |
| Check Results | Buffered in Redis Stream, flushed every 5s | TimescaleDB hypertable, partitioned by day |
| Active Incidents | HSET active_incident:{monitor_id} → incident_id, started_at | Incident table with full history |

---

## 4. API Routes

```
POST   /api/v1/monitors
  Body: { name, url, method?, intervalSec?, timeoutSec?, regions[]?, expectedStatus?, headers?, bodyPattern?, sslCheck? }
  Response: { monitorId, status: "active", nextCheckAt }
  Errors: 400 (invalid URL), 402 (plan limit), 409 (duplicate URL)

GET    /api/v1/monitors?status=active&page=1&limit=20
  Response: { monitors[], total, page }

GET    /api/v1/monitors/{monitorId}
  Response: { config + currentStatus + uptime24h + avgResponseTime }
  Errors: 404, 403

PUT    /api/v1/monitors/{monitorId}
  Body: { partial config }
  Response: { updated monitor }

DELETE /api/v1/monitors/{monitorId}
  Response: 204 (soft delete)

GET    /api/v1/monitors/{monitorId}/checks?from=ISO&to=ISO&region=us-east&limit=100
  Response: { checks[], pagination }

GET    /api/v1/monitors/{monitorId}/incidents?from=ISO&to=ISO&status=resolved
  Response: { incidents[] }

GET    /api/v1/monitors/{monitorId}/metrics?from=ISO&to=ISO&granularity=1h
  Response: { datapoints: [{ bucket, avgResponseMs, p95ResponseMs, uptimePercent }] }

POST   /api/v1/alert-channels
  Body: { type: "slack"|"email"|"webhook"|"sms", config: {...} }
  Response: { channelId, verified: false }
  Note: triggers verification (email confirm, Slack test, webhook ping)

POST   /api/v1/monitors/{monitorId}/alerts
  Body: { channelId, notifyOn: ["down","recovered","ssl_expiring"], cooldownMin? }
  Response: { binding config }

Auth: API key or JWT on all routes
Rate limit: 100 req/min free, 1000 req/min pro
```

---

## 5. High-Level Design

```
┌──────────────┐     ┌──────────────┐
│  Web         │     │  API         │
│  Dashboard   │     │  Clients     │
│  (React SPA) │     │  (curl, SDK) │
└──────┬───────┘     └──────┬───────┘
       │                     │
       └──────────┬──────────┘
                  │
          ┌───────▼────────┐
          │  API Gateway   │  auth (JWT + API key), rate limit,
          │                │  plan enforcement
          └───────┬────────┘
                  │
    ┌─────────────┼─────────────┐
    │             │             │
┌───▼────┐  ┌────▼────┐  ┌────▼─────┐
│Monitor │  │Metrics  │  │Alert     │
│CRUD    │  │Query    │  │Management│
│Service │  │Service  │  │Service   │
└───┬────┘  └────┬────┘  └────┬─────┘
    │             │             │
    │        reads from    consumes from
    │        TimescaleDB   Kafka "alerts"
    │             │             │
    └──────┬──────┘             │
           │                    │
   ┌───────▼───────┐           │
   │  Scheduler    │           │                 Per-region fleet:
   │  Service      │           │                   10 pods × 500 conn = 5K concurrent/region
   └───────┬───────┘           │                   6 regions total
           │                   │                   Circuit breaker: 10 fails → throttle 1/5
   polls Redis ZSET            │
   (atomic Lua pop)            │
           │                   │
   ┌───────▼───────┐          │
   │ Check Workers │          │                  Incident detection:
   │ (per-region   │          │                    quorum: ≥ ceil(regions/2) must agree
   │  fleet)       │          │                    hysteresis: 2 consecutive down → incident
   └───────┬───────┘          │                    2 consecutive up → resolved
           │                   │
   publishes to Kafka          │
   "check-results"             │
           │                   │
   ┌───────▼───────┐          │
   │ Result        │          │
   │ Ingestion     ├──────────┘
   │ Pipeline      │
   └──┬─────┬──────┘
      │     │
      │     └──▶ state change? → create/resolve Incident → publish ALERT_TRIGGER
      │
      ▼ writes to:
┌─────────────┐    ┌──────────────┐    ┌──────────────┐
│  Redis      │    │ TimescaleDB  │    │ PostgreSQL   │
│  ┌────────┐ │    │ ┌──────────┐ │    │ ┌──────────┐ │
│  │schedule│ │    │ │check     │ │    │ │monitors  │ │
│  │ZSET    │ │    │ │results   │ │    │ │users     │ │
│  │status  │ │    │ │hypertable│ │    │ │incidents │ │
│  │cache   │ │    │ │metric    │ │    │ │alert     │ │
│  │active  │ │    │ │rollups   │ │    │ │channels  │ │
│  │incident│ │    │ │(hourly,  │ │    │ │(source   │ │
│  └────────┘ │    │ │ daily)   │ │    │ │ of truth)│ │
└─────────────┘    │ └──────────┘ │    └──────────────┘
                   └──────────────┘
```

### Component Responsibilities

| Component | Role |
|---|---|
| API Gateway | API key / JWT auth, rate limiting, request routing |
| Monitor CRUD Service | Create/update/delete monitors, validate URLs, enforce plan limits |
| Scheduler Service | Time-ordered queue of due monitors, dispatches to regional workers |
| Check Workers | Per-region fleet — executes HTTP checks, measures response time, validates SSL |
| Result Ingestion Pipeline | Consumes results from Kafka, detects state changes, triggers alerts, writes to storage |
| Metrics Query Service | Reads from TimescaleDB, computes uptime %, serves dashboard charts |
| Alert Management Service | Manages channels, sends notifications, handles cooldowns + escalation |
| Redis | Scheduler ZSET, status cache, active incident tracking, rate limiting |
| Kafka | check-results stream (partitioned by monitor_id), alerts stream |
| TimescaleDB | Time-series storage for check results + metric rollups |
| PostgreSQL | Source of truth for monitors, users, incidents, alert channels |

### Data Flow: Periodic Health Check

```
Scheduler polls Redis ZSET → pops due monitors (atomic Lua)
    │
    ▼
Publishes CHECK_DUE to Kafka "check-dispatch" per region
Re-adds monitor to ZSET with score = now + interval + 5% jitter
    │
    ▼
Check Workers consume from "check-dispatch"
Execute HTTP request (method, headers, timeout)
Measure DNS, TCP, TLS, TTFB, total response time
    │
    ▼
Publish CHECK_RESULT to Kafka "check-results"
    │
    ▼
Result Ingestion Pipeline:
  1. Write to TimescaleDB
  2. Update Redis status cache (is_up, last_check, response_ms)
  3. Compare with previous state
     └─ state changed? → create/resolve Incident → publish ALERT_TRIGGER
    │
    ▼
Alert Service consumes ALERT_TRIGGER
  → looks up bindings → sends notifications (respecting cooldowns)
```

### Data Flow: Incident Detection

```
Check Worker (us-east): MON-88201 → timeout → is_up=false
Check Worker (eu-west): MON-88201 → timeout → is_up=false

Ingestion receives us-east result:
  previous state = UP, but don't alert yet — wait for other regions

Ingestion receives eu-west result:
  2/2 regions down → confirmed outage
  → Create Incident INC-XXXX
  → Publish ALERT_TRIGGER

If only 1/2 down → mark "degraded", no alert (could be regional blip)
```

Multi-region confirmation: declared "down" only when ≥ ceil(regions/2) report failure in same check cycle.

### Technology Justification

| Choice | Why | Alternatives Considered |
|---|---|---|
| TimescaleDB | Purpose-built time-series, auto-partitioning, continuous aggregates | InfluxDB (less SQL), plain Postgres (no auto-partition) |
| PostgreSQL | ACID for configs, users, incidents, billing | DynamoDB (overkill, less flexible) |
| Redis | Sub-ms scheduler reads, status cache, incident tracking | Postgres (too slow for scheduler hot loop) |
| Kafka | Ordered result stream, decouples workers from ingestion, replay | SQS (no ordering), RabbitMQ (no replay) |
| Per-region workers | Multi-geo checks detect regional vs global outages | Single-region (can't distinguish) |

---

## 6. Deep Dives

### Deep Dive 1: Distributed Scheduler — Time-Wheel with Redis ZSET

**Problem:** 200K active monitors, intervals 15s–5min, must fire on time. Naive cron-per-monitor doesn't scale. Need even distribution, no thundering herds, crash recovery.

**Approach:** Redis Sorted Set as a global time-wheel. score = next_check_epoch, member = monitor_id.

```
Scheduler Instance (any of N)
    │
    ▼
ZRANGEBYSCORE + ZREM in one Lua script     ← atomic, no double-scheduling
(pop all monitors where score ≤ now, batch of 100)
    │
    ▼
For each popped monitor:
  read config from HSET "monitor:{id}"
  publish CHECK_DUE to Kafka for each configured region
  re-add to ZSET: score = now + interval + 5% jitter
    │
    ▼
No work? → sleep 100ms (avoid busy-spin)
```

Jitter: 5% random offset. 1000 monitors at 60s spread over 3-second window instead of all at :00.

Crash recovery: if instance dies after pop but before re-add, monitors become "orphaned." Reconciliation job every 60s queries PostgreSQL for all active monitors, diffs against ZSET, re-adds missing ones with randomized next-check. Orphan window bounded to ~60s.

Scaling: Lua pop is atomic → N instances run concurrently, zero coordination. Just add instances.

Latency: ZPOPMIN of 100 items < 2ms. Kafka publish < 5ms each. Batch of 100: < 50ms total.

Trade-off: Redis is SPOF for scheduler. Mitigate with Sentinel/Cluster. If Redis dies, reconciliation re-populates from PostgreSQL on recovery.

> "Redis ZSET as distributed time-wheel — atomic ZPOPMIN prevents double-scheduling, 5% jitter prevents thundering herd, reconciliation recovers orphans within 60 seconds."

---

### Deep Dive 2: Check Workers — Multi-Region Execution

**Problem:** Each check needs detailed perf metrics. Workers must handle 3,333 concurrent connections/region at peak.

**Approach:** Async HTTP client with connection pooling, circuit breakers.

```
CHECK_DUE arrives from Kafka
    │
    ▼
Async HTTP client (500 conn/pod, 100 keep-alive, follow redirects max 3)
Timeouts: connect 5s, read 10s, write 5s
    │
    ▼
Issue request → capture elapsed via monotonic clock
    │
    ├─ 2xx + matches expected status + body pattern? → is_up = true
    ├─ non-2xx or body mismatch?                     → is_up = false
    ├─ timeout?                                       → is_up = false, error = "timeout"
    └─ connection error?                              → is_up = false, error = "conn_error"
    │
    ▼
HTTPS? → also check SSL cert expiry (TLS connect, read notAfter, compute days remaining)
    │
    ▼
Publish CHECK_RESULT to Kafka "check-results"
```

Concurrency: 10 pods/region × 500 connections = 5K concurrent/region. 6 regions → 30K total (peak need: 15K).

Circuit breaker (per-monitor in Redis): 10 consecutive failures → reduce check rate to 1/5th. 1 success → reset.

Latency: dominated by target response (avg 200ms). Worker overhead < 10ms. Kafka publish < 5ms.

Trade-off: async HTTP can't capture per-phase timing (DNS, TCP, TLS separately) as precisely as raw sockets. V1 uses total response time. V2: custom transport hooks for breakdown.

> "Async HTTP workers, 500 conn/pod, circuit breaker throttles confirmed-down to 1/5th, 10 pods/region handles 5K concurrent."

---

### Deep Dive 3: Incident Detection — Multi-Region Consensus with Hysteresis

**Problem:** Single failed check ≠ site down. Could be transient blip or regional issue. Must distinguish true outages from false positives while alerting within 30s.

**Approach:** Multi-region voting + hysteresis (N consecutive failures → down, N consecutive successes → recovered).

```
CHECK_RESULT arrives
    │
    ▼
Store per-region result in Redis hash "check_latest:{monitor_id}"
  region → { is_up, timestamp }
    │
    ▼
All configured regions reported for this cycle?
  No  → wait
  Yes → count down_votes
    │
    ▼
down_votes ≥ ceil(total_regions × 0.5)?
  Yes → cycle is "down"
  No  → cycle is "up"
    │
    ▼
Hysteresis counters in Redis:

  Currently UP + cycle down:
    increment down counter, reset up counter
    down counter ≥ 2 (DOWN_THRESHOLD)?
      → open incident, flip to DOWN, trigger alert

  Currently DOWN + cycle up:
    increment up counter, reset down counter
    up counter ≥ 2 (UP_THRESHOLD)?
      → resolve incident, flip to UP, trigger recovery alert

  Cycle matches current state → reset opposite counter
```

Incident lifecycle: open → insert into PostgreSQL + store ref in Redis. Close → update resolved_at + duration, remove Redis ref, notify.

Why hysteresis? Without it: single packet drop → down alert → recovery 60s later = noise. With threshold=2 at 60s interval: 2min to confirm. At 15s (Enterprise): 30s. Users can override to 1 for critical monitors.

Alert delivery latency: check ~200ms → Kafka ~50ms → detection ~10ms → trigger ~20ms → webhook ~200ms / Slack ~500ms / email ~2s / SMS ~3s. Total webhook: < 500ms. SMS: < 5s.

Trade-off: hysteresis adds 2 check cycles of latency. Configurable per monitor.

> "Multi-region quorum + hysteresis (2 consecutive failures to alert, 2 successes to recover) — eliminates false positives, detection under 2 check cycles."

---

### Deep Dive 4: Time-Series Storage — TimescaleDB with Continuous Aggregates

**Problem:** 72 GB/day raw check results. Dashboards need fast aggregations over arbitrary ranges. Raw retained 90d, aggregates 2yr.

**Approach:** TimescaleDB hypertable + continuous aggregates + native compression.

```
Raw check results
    │
    ▼
TimescaleDB hypertable: check_results
  partitioned by checked_at, chunk interval = 1 day
  indexes: (monitor_id, checked_at DESC), (region, checked_at DESC)
    │
    ▼
Continuous aggregates (incrementally maintained, no full-table scans):
  ┌─────────────────────────────────────────────────────┐
  │ Hourly rollup (refreshed every 10min):              │
  │   per monitor: avg_response_ms, p95_response_ms,    │
  │   check_count, up_count, down_count, uptime_percent │
  ├─────────────────────────────────────────────────────┤
  │ Daily rollup: same metrics at day granularity       │
  └─────────────────────────────────────────────────────┘
    │
    ▼
Retention: auto-drop raw chunks > 90 days
Compression: chunks > 7 days, segmented by monitor_id, ordered by checked_at DESC
  90%+ compression → 6.5 TB raw → ~650 GB compressed
Aggregates retained 2 years
```

Query performance:
- Last 24h, single monitor (1 chunk): < 10ms
- Last 30d hourly rollup (continuous agg): < 20ms
- Last 1yr daily rollup: < 50ms

Uptime calculation: query hourly aggregate, sum up_count/check_count. 30-day window reads ~720 pre-computed rows instead of millions of raw results.

Why TimescaleDB over plain PostgreSQL? At 72 GB/day, plain PG needs manual partitioning, manual rollup cron, manual compression. TimescaleDB automates all three. Fully PG-compatible — same drivers, SQL, ecosystem.

Trade-off: operational complexity (extension mgmt, chunk tuning). But building custom partitioning + rollups + compression on plain PG is more engineering effort and more error-prone.

> "TimescaleDB hypertable with continuous aggregates — 72 GB/day raw auto-compressed to ~7 GB, hourly/daily rollups pre-computed, 30-day uptime query in < 20ms."

---

### Deep Dive 5: Alert Pipeline — Deduplication, Cooldowns, and Escalation

**Problem:** Major outage → hundreds of monitors down simultaneously. Without dedup + cooldowns, users get flooded. Must be reliable but not noisy.

**Approach:** Per-monitor cooldowns, per-user rate limiting, escalation chains.

```
ALERT_TRIGGER arrives (event = "down" or "recovered")
    │
    ▼
Look up MonitorAlertBindings for this monitor
    │
    ▼
For each binding:
  event matches notify_on? (e.g. "down,recovered")
    No  → skip
    Yes ↓
    │
    ▼
  Cooldown check: Redis key "alert_cooldown:{monitor}:{channel}"
    exists + event is "down"? → skip (already alerted recently)
    recovery alerts always bypass cooldowns
    │
    ▼
  Per-user rate limit: Redis counter "alert_rate:{user_id}"
    > 20 in current 60s window? → queue into digest list
    │
    ▼
  Send via notifier (email / Slack / webhook / SMS)
    success → set cooldown key with TTL = cooldown_min (default 5min)
    failure → retry 3× exponential backoff (10s, 30s, 90s)
              after 3 fails → "delivery_failed" → try fallback channel
```

Digest: when rate-limited, alerts pushed to Redis list with 5min TTL. Background job every 5min sends single digest email grouped by event type.

Escalation: background job every 1min queries unresolved incidents > 30min, not yet escalated. Sends via email + SMS regardless of original config. Marks escalated.

Trade-off: cooldowns mean no re-alert if monitor flaps within window. Intentional — flapping should be investigated, not spammed. Users can set cooldown=0 for critical monitors.

> "Per-monitor cooldowns prevent alert storms, per-user rate limiting (20/min) with digest fallback, 30-min escalation for unresolved — reliable without being noisy."
