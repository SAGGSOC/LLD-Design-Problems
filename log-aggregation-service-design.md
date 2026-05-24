# Log Aggregation Service — System Design

---

## 1. Functional Requirements

- `ingestLogs(sourceId, logs[]) → {accepted, sequenceId}` — accept batches of structured/unstructured logs from agents
- `search(query, timeRange, sources[], filters) → {results[], totalHits, cursor}` — full-text search with field filters across all ingested logs
- `tailLogs(sourceId, filters) → stream` — real-time live tail of incoming logs (WebSocket)
- `createAlert(query, condition, channels[]) → alertId` — trigger notifications when a query matches N times in a window
- `getDashboard(dashboardId) → {panels[]}` — pre-built visualizations: log volume, error rates, top patterns
- `getLogContext(logId, range) → surroundingLogs[]` — fetch N lines before/after a specific log entry for debugging

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| Log sources? | Application servers, containers (K8s), Lambda, load balancers, CDN, custom agents |
| Structured or unstructured? | Both. JSON preferred, but support raw syslog, plaintext, multiline stack traces |
| Max log line size? | 64 KB per event. Truncate beyond that. |
| Ingestion protocol? | HTTPS (agent push), Kafka (direct), syslog (UDP/TCP 514) |
| Query language? | Lucene-like: `level:ERROR AND service:"payment-api" AND "timeout"` |
| Retention tiers? | Hot: 7d (searchable, fast). Warm: 30d (searchable, slower). Cold: 1yr (archive, restore on demand). |
| Multi-tenancy? | Yes. Org-level isolation. Each org sees only its own logs. |
| Compliance? | Logs immutable once written. Audit trail for access. GDPR: field-level masking for PII. |

### Assumptions

- 500 organizations, 50K total log sources (servers, containers, functions)
- Average log volume: 2 TB/day ingested (compressed). Peak 3× during incidents.
- 50K events/sec steady, 150K events/sec peak
- Average event size: 500 bytes (after JSON normalization)
- Search QPS: 200 steady, 1K peak (during incident investigation)
- Live tail: 5K concurrent WebSocket connections
- 80% of searches target last 1 hour of data


---

## 2. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Scale | 50K sources, 2 TB/day, 150K events/sec peak ingestion |
| Throughput | 50K writes/sec steady; 200 search QPS steady, 1K peak |
| Latency | Ingest-to-searchable < 5s; search p95 < 500ms (hot tier); live tail < 2s |
| Read/write ratio | 1:250 (writes dominate; reads spike during incidents) |
| Consistency | Eventual for search index; strong for alert rules + org config |
| Availability | 99.95% for ingestion (never drop logs); 99.9% for search |
| Durability | Zero log loss after ACK. Replicated before acknowledgment. |
| Constraints | Multi-tenant isolation; immutable logs; PII masking; field-level RBAC |

### Back-of-Envelope

- Storage (raw): 2 TB/day × 7d hot = 14 TB hot. 30d warm = 60 TB. 1yr cold = 730 TB (compressed ~10:1 = 73 TB on S3)
- Events: 50K/sec × 500B = 25 MB/sec steady. Peak 75 MB/sec.
- Index size: ~30% of raw → hot tier index ~4.2 TB (7d). Warm index ~18 TB (30d).
- Kafka throughput: 150K events/sec × 500B = 75 MB/sec. 3× replication = 225 MB/sec disk I/O.
- Search fanout: query hits all shards in time range. 7d hot = 7 daily shards × N replicas.
- Live tail: 5K WS connections × avg 100 events/sec matched = 500K events/sec fan-out.

---

## 3. Core Entities

**Organization:**
```
org_id (PK) | name           | plan        | retention_days | daily_limit_gb | api_key_hash | created_at
"ORG-4201"  | "Acme Corp"   | "business"  | 30             | 500            | sha256:...   | 2025-09-01T00:00:00
```

**LogSource:**
```
source_id (PK) | org_id   | name              | type       | environment | tags_json              | agent_version | last_seen_at
"SRC-88201"    | ORG-4201 | "payment-api-pod" | "k8s"      | "production"| {"cluster":"us-east"}  | "2.4.1"       | 2026-03-14T08:30:00
```

**LogEvent (Elasticsearch / OpenSearch):**
```
_id            | org_id   | source_id  | timestamp           | level | service       | message                          | fields_json                    | trace_id     | span_id
"EVT-a1b2c3"  | ORG-4201 | SRC-88201  | 2026-03-14T08:30:01 | ERROR | payment-api   | "Connection timeout to db-primary"| {"host":"10.0.1.5","latency":0}| "trc-9f8e7d" | "spn-1a2b"
```

**AlertRule:**
```
alert_id (PK) | org_id   | name                  | query                              | condition         | window_min | channels[]     | cooldown_min | status
"ALR-301"     | ORG-4201 | "Payment errors spike"| "level:ERROR AND service:payment*" | "count > 50"      | 5          | [slack,email]  | 15           | active
```

**AlertChannel:**
```
channel_id (PK) | org_id   | type    | config_json                        | verified
"ACH-101"       | ORG-4201 | slack   | {"webhook_url":"https://hooks..."} | true
```

**SavedSearch:**
```
search_id (PK) | org_id   | user_id  | name                | query                                    | filters_json | created_at
"SS-501"       | ORG-4201 | USR-1001 | "Prod error triage" | "level:ERROR AND env:production"         | {"last":"1h"}| 2026-03-10T14:00:00
```

**Dashboard:**
```
dashboard_id (PK) | org_id   | name              | panels_json                                                    | created_at
"DSH-201"         | ORG-4201 | "Ops Overview"    | [{"type":"timeseries","query":"*","agg":"count","interval":"1m"}] | 2026-03-01T10:00:00
```

### Entity Relationships
```
Organization ──1:N──▶ LogSources ──1:N──▶ LogEvents
Organization ──1:N──▶ AlertRules ──N:N──▶ AlertChannels
Organization ──1:N──▶ SavedSearches
Organization ──1:N──▶ Dashboards
Organization ──1:N──▶ Users (RBAC)
LogEvent optionally links to trace_id/span_id (distributed tracing correlation)
```

### Hot-Path vs Cold-Path

| Entity | Hot Path | Cold Path |
|---|---|---|
| Log Events (0–7d) | OpenSearch hot nodes (SSD, full-text indexed) | — |
| Log Events (7–30d) | OpenSearch warm nodes (HDD, read-only indices) | — |
| Log Events (30d–1yr) | — | S3 (compressed Parquet), restore to warm on demand |
| Live Tail | Kafka consumer group, direct stream to WebSocket | — |
| Alert State | Redis (sliding window counters per alert rule) | PostgreSQL (alert history, audit) |
| Org Config | Redis cache (TTL 5min) | PostgreSQL (source of truth) |

---

## 4. API Routes

```
POST   /api/v1/ingest
  Headers: { "X-Api-Key": "...", "Content-Encoding": "gzip" }
  Body: { "source": "SRC-88201", "events": [
    { "timestamp": "ISO", "level": "ERROR", "message": "...", "fields": {...} }
  ]}
  Response: { "accepted": 1842, "sequenceId": "seq-0014a", "errors": 0 }
  Errors: 400 (malformed), 401 (bad key), 413 (batch too large), 429 (rate limited)

POST   /api/v1/search
  Body: { "query": "level:ERROR AND service:payment*",
          "from": "2026-03-14T07:00:00Z", "to": "2026-03-14T08:30:00Z",
          "sources": ["SRC-88201"], "limit": 100, "cursor": "..." }
  Response: { "hits": [...], "totalHits": 4821, "cursor": "next-abc", "took_ms": 42 }
  Errors: 400 (invalid query syntax), 408 (query timeout)

GET    /api/v1/logs/{logId}/context?before=20&after=20
  Response: { "before": [...], "target": {...}, "after": [...] }

WS     /api/v1/tail?source=SRC-88201&filter=level:ERROR
  Push: { "timestamp": "...", "level": "ERROR", "message": "...", "fields": {...} }

POST   /api/v1/alerts
  Body: { "name": "...", "query": "...", "condition": "count > 50",
          "windowMin": 5, "channels": ["ACH-101"], "cooldownMin": 15 }
  Response: { "alertId": "ALR-301", "status": "active" }

GET    /api/v1/alerts?status=active
  Response: { "alerts": [...] }

GET    /api/v1/alerts/{alertId}/history?from=ISO&to=ISO
  Response: { "firings": [{ "firedAt": "...", "matchCount": 87, "notified": [...] }] }

POST   /api/v1/dashboards
  Body: { "name": "...", "panels": [...] }
  Response: { "dashboardId": "DSH-201" }

GET    /api/v1/dashboards/{dashboardId}/data?from=ISO&to=ISO
  Response: { "panels": [{ "panelId": "...", "datapoints": [...] }] }

GET    /api/v1/sources?environment=production&type=k8s
  Response: { "sources": [...], "total": 142 }

Auth: API key (agents) or JWT (dashboard users) on all routes
Rate limit: ingest 10K events/sec per source; search 100 req/min per user
Tenant isolation: org_id injected from auth token, appended to every query
```


---

## 5. High-Level Design

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Log Agents  │     │  Web         │     │  API         │
│  (fluentd,   │     │  Dashboard   │     │  Clients     │
│   vector,    │     │  (React SPA) │     │  (curl, SDK) │
│   custom)    │     └──────┬───────┘     └──────┬───────┘
└──────┬───────┘            │                     │
       │                    └──────────┬──────────┘
       │                               │
       │  HTTPS / syslog        ┌──────▼───────┐
       │                        │  API Gateway │  auth, rate limit,
       └───────────────────────▶│              │  tenant isolation
                                └──────┬───────┘
                                       │
                  ┌────────────────────┼────────────────────┐
                  │                    │                    │
          ┌───────▼────────┐  ┌───────▼────────┐  ┌───────▼────────┐
          │  Ingest        │  │  Search        │  │  Alert         │
          │  Service       │  │  Service       │  │  Management    │
          │  (validate,    │  │  (parse query, │  │  Service       │
          │   enrich,      │  │   fan-out to   │  │  (CRUD rules,  │
          │   route)       │  │   OpenSearch)  │  │   view history)│
          └───────┬────────┘  └───────┬────────┘  └───────┬────────┘
                  │                    │                    │
          publishes to            reads from          consumes from
          Kafka "raw-logs"        OpenSearch           Kafka "alerts"
                  │                    │                    │
          ┌───────▼────────┐          │                    │
          │  Stream        │          │                    │
          │  Processor     │          │                    │
          │  (parse,       │          │                    │
          │   normalize,   │          │                    │
          │   PII mask,    │          │                    │
          │   enrich)      │          │                    │
          └──┬──────┬──────┘          │                    │
             │      │                 │                    │
             │      └──▶ Kafka "processed-logs"            │
             │                │                            │
             │      ┌─────────┼─────────┐                  │
             │      │         │         │                  │
             │      ▼         ▼         ▼                  │
             │  ┌────────┐ ┌────────┐ ┌────────┐          │
             │  │Index   │ │Tail    │ │Alert   │          │
             │  │Writer  │ │Fanout  │ │Evaluator├─────────┘
             │  │Service │ │Service │ │Service │
             │  └───┬────┘ └───┬────┘ └───┬────┘
             │      │          │          │
             │  writes to   pushes to  sliding window
             │  OpenSearch  WebSocket  counters in Redis
             │      │          │          │
             │      ▼          │          │
             │  ┌─────────────────────────────────┐
             │  │         OpenSearch Cluster       │
             │  │  ┌──────────┐  ┌──────────────┐ │
             │  │  │ Hot Nodes│  │ Warm Nodes   │ │
             │  │  │ (SSD)    │  │ (HDD)        │ │
             │  │  │ 0–7 days │  │ 7–30 days    │ │
             │  │  └──────────┘  └──────────────┘ │
             │  └─────────────────────────────────┘
             │
             ▼ archives to:
       ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
       │  S3          │    │  Redis       │    │  PostgreSQL  │
       │  ┌─────────┐ │    │  ┌─────────┐ │    │  ┌─────────┐ │
       │  │cold logs│ │    │  │alert    │ │    │  │orgs     │ │
       │  │(Parquet,│ │    │  │counters │ │    │  │sources  │ │
       │  │ gzip,   │ │    │  │rate     │ │    │  │alerts   │ │
       │  │ 30d–1yr)│ │    │  │limits   │ │    │  │dashboards│ │
       │  └─────────┘ │    │  │org cache│ │    │  │users    │ │
       └──────────────┘    │  └─────────┘ │    │  │(source  │ │
                           └──────────────┘    │  │ of truth)│ │
                                               └──────────────┘
```

### Component Responsibilities

| Component | Role |
|---|---|
| API Gateway | API key / JWT auth, rate limiting, tenant isolation (inject org_id) |
| Ingest Service | Validate schema, decompress, assign sequence IDs, publish to Kafka |
| Stream Processor | Parse unstructured logs, normalize fields, mask PII, enrich with geo/host metadata |
| Index Writer Service | Consume processed logs, bulk-index into OpenSearch (batched every 1s or 5K docs) |
| Tail Fanout Service | Consume processed logs, match against active tail subscriptions, push via WebSocket |
| Alert Evaluator Service | Consume processed logs, maintain sliding window counters in Redis, fire alerts on threshold breach |
| Search Service | Parse Lucene-like query, add org_id filter, fan-out to OpenSearch, merge + paginate results |
| Alert Management Service | CRUD for alert rules, view firing history, manage channels |
| OpenSearch (Hot) | Full-text indexed, SSD-backed, 0–7 day logs. Primary search target. |
| OpenSearch (Warm) | Read-only indices on HDD, 7–30 day logs. Slower but cheaper. |
| S3 Cold Archive | Compressed Parquet, 30d–1yr. Restore to warm on demand (minutes). |
| Kafka | raw-logs topic (pre-processing), processed-logs topic (post-processing). Partitioned by org_id. |
| Redis | Alert sliding window counters, rate limiting, org config cache |
| PostgreSQL | Source of truth for orgs, sources, alert rules, dashboards, users, RBAC |

### Data Flow: Log Ingestion (Write Path)

```
Agent batches logs (gzip compressed, HTTPS POST)
    │
    ▼
API Gateway: authenticate API key → resolve org_id → rate limit check
    │
    ▼
Ingest Service:
  1. Decompress, validate schema (reject malformed, count errors)
  2. Assign monotonic sequence ID per source
  3. Publish to Kafka "raw-logs" (partitioned by org_id)
  4. ACK to agent immediately (logs are durable in Kafka)
    │
    ▼
Stream Processor (Kafka consumer group, N partitions):
  1. Parse: detect format (JSON, syslog, plaintext), extract fields
  2. Normalize: map to common schema (timestamp, level, service, message, fields)
  3. PII mask: regex scan for emails, IPs, credit cards → replace with [REDACTED]
  4. Enrich: resolve source_id → add environment, cluster, region tags
  5. Publish to Kafka "processed-logs"
    │
    ▼
Three parallel consumers on "processed-logs":
  ├─ Index Writer  → bulk insert to OpenSearch (batch 5K docs or 1s window)
  ├─ Tail Fanout   → match against active tail filters → push to WebSocket clients
  └─ Alert Evaluator → increment sliding window counters → fire if threshold breached
```

### Data Flow: Log Search (Read Path)

```
User submits query: "level:ERROR AND service:payment* AND timeout"
    │
    ▼
Search Service:
  1. Parse Lucene-like query into OpenSearch DSL
  2. Inject org_id filter (tenant isolation — always appended, never optional)
  3. Determine time range → select target indices (hot vs warm)
  4. Fan-out query to OpenSearch
  5. Merge results, sort by timestamp, apply pagination
    │
    ▼
OpenSearch:
  Hot nodes (0–7d): query across daily indices, return top N
  Warm nodes (7–30d): query read-only indices if time range extends beyond 7d
    │
    ▼
Return to user: { hits[], totalHits, cursor, took_ms }
```

### Technology Justification

| Choice | Why | Alternatives Considered |
|---|---|---|
| OpenSearch | Full-text search + analytics on semi-structured logs, native Lucene | Elasticsearch (license concerns), Loki (limited query power) |
| Kafka | Durable buffer decouples ingestion from indexing, replay on failure, ordered per partition | Kinesis (vendor lock-in), Pulsar (less ecosystem) |
| S3 + Parquet | Cheapest cold storage, columnar format for ad-hoc analytics with Athena/Spark | Glacier (too slow restore), HDFS (operational overhead) |
| PostgreSQL | ACID for org config, alert rules, RBAC, dashboards | DynamoDB (overkill for config data) |
| Redis | Sub-ms sliding window counters for alerts, rate limiting, config cache | In-process counters (lost on restart, no cross-instance) |
| Stream Processor (Flink/custom) | Stateful processing: PII masking, enrichment, parsing at 150K events/sec | Lambda (cold start), Logstash (single-threaded, slow) |

### Why Not an OLAP DB or Time-Series DB?

| Alternative | Strengths for Logs | Why Not Primary Store |
|---|---|---|
| ClickHouse (OLAP) | Blazing columnar aggregations, excellent compression (10–20:1), SQL interface, fast GROUP BY on billions of rows | Weak full-text search — no inverted index, `LIKE '%timeout%'` scans entire column. Log search is 80% keyword/phrase lookup. |
| Apache Druid (OLAP) | Real-time ingestion + fast slice-and-dice on dimensions, good for dashboards | Same full-text limitation. No native phrase search, proximity, or fuzzy matching. |
| TimescaleDB (TSDB) | Great for numeric time-series (metrics, counters), continuous aggregates, SQL | Designed for numeric datapoints, not variable-length text. Full-text via `tsvector` is far slower than Lucene at scale. |
| InfluxDB (TSDB) | Purpose-built for metrics, excellent compression for numeric series | No full-text search at all. Tags are low-cardinality only. Log messages are high-cardinality strings — fundamentally mismatched. |
| Loki (log-specific) | Cheap — indexes only labels, not log content. Pairs with Grafana. | Queries scan raw chunks filtered by labels. `grep`-style, not indexed search. Slow for "find all ERROR logs containing 'timeout' across 500 services in last 7 days." |

The core issue: logs are semi-structured text with high-cardinality fields. The primary access pattern is keyword/phrase search ("find me all logs containing 'connection refused' from service X in the last hour"). This requires an inverted index (Lucene). OLAP and TSDB engines use columnar storage optimized for numeric aggregations — they're fast at "count errors per service per minute" but slow at "find the 3 log lines containing this stack trace."

### Hybrid Architecture: OpenSearch + ClickHouse

For orgs that need both fast search AND heavy analytics, a hybrid approach works well:

```
Kafka "processed-logs"
    │
    ├──▶ Index Writer → OpenSearch (full-text search, live tail, alerting)
    │
    └──▶ OLAP Writer  → ClickHouse (aggregation dashboards, trend analysis)
```

| Workload | Route to | Why |
|---|---|---|
| "Find logs containing 'OOM killed'" | OpenSearch | Inverted index, phrase search, < 500ms |
| "Error count by service, last 7 days, 1-min buckets" | ClickHouse | Columnar scan, GROUP BY, < 100ms on billions of rows |
| "Top 10 error patterns this week" | ClickHouse | Aggregation with approximate COUNT DISTINCT |
| "Show me the log context around this error" | OpenSearch | Document retrieval by ID + range scan by timestamp |
| "P99 response time per endpoint per hour" | ClickHouse | Numeric aggregation, continuous materialized views |

ClickHouse ingestion: same Kafka consumer group writes to ClickHouse using a MergeTree table partitioned by (org_id, toDate(timestamp)). Compression ratio ~15:1 on log data. 2 TB/day raw → ~130 GB/day in ClickHouse.

This is the approach Datadog, Grafana Cloud, and similar platforms converge on — inverted index for search, columnar engine for analytics. Neither alone covers both workloads well.


---

## 6. Deep Dives

### Deep Dive 1: Ingestion Pipeline — Handling 150K Events/Sec Without Data Loss

**Problem:** Agents push logs in bursts (deploy spikes, error storms). Must never drop logs after ACK. Must handle 3× peak without backpressure reaching agents. Parsing and enrichment are CPU-heavy — can't do them synchronously in the ingest path.

**Approach:** Two-stage pipeline. Stage 1 (Ingest Service) is fast and dumb — validate, assign ID, dump to Kafka. Stage 2 (Stream Processor) is slow and smart — parse, normalize, enrich, mask. Kafka absorbs the gap.

```
Agent POST /ingest (gzip, batch of 500 events)
    │
    ▼
Ingest Service (stateless, 20 pods):
  decompress → validate timestamp exists → assign sequence_id
  publish to Kafka "raw-logs" partition = hash(org_id) % 64
  ACK to agent (200 OK with sequence_id)
    │
    ▼
Kafka "raw-logs" (64 partitions, replication factor 3, retention 24h):
  acts as durable buffer — if Stream Processor falls behind, logs queue safely
    │
    ▼
Stream Processor (Flink job, 32 task slots):
  consume from "raw-logs" → parse → normalize → PII mask → enrich
  publish to "processed-logs" (64 partitions)
```

Why 64 Kafka partitions? At 150K events/sec and ~5K events/sec per consumer thread, need ~30 consumers. 64 partitions gives headroom for 2× growth without repartitioning.

Backpressure: if Stream Processor lags, Kafka buffers up to 24h of raw logs (~6.5 TB at peak). Agents never see backpressure. If Kafka itself is full (catastrophic), Ingest Service returns 503 and agents retry with exponential backoff (agent-side disk buffer holds ~1 GB).

Exactly-once semantics: Kafka producer uses idempotent mode (enable.idempotence=true). Stream Processor uses Flink checkpointing with Kafka transactions. Index Writer uses OpenSearch bulk with document IDs derived from sequence_id — duplicate inserts are idempotent (same _id = upsert).

Latency: agent POST → Kafka ACK < 20ms. Kafka → Stream Processor → processed-logs < 200ms. Index Writer bulk flush < 1s. Total ingest-to-searchable: < 2s typical, < 5s p99.

Trade-off: two Kafka topics doubles storage. But decoupling raw from processed means you can reprocess (replay raw-logs with updated parsing rules) without re-ingesting from agents.

> "Two-stage pipeline — fast ACK to agents via Kafka, async parse/enrich/mask in Stream Processor, 64 partitions handle 150K events/sec, 24h Kafka buffer absorbs any downstream lag."

---

### Deep Dive 2: OpenSearch Index Strategy — Hot/Warm/Cold Tiering

**Problem:** 2 TB/day raw logs. Keeping 30 days fully indexed on SSD is 60 TB — expensive. But 80% of searches target last 1 hour. Need to optimize cost without sacrificing recent-data performance.

**Approach:** Time-based daily indices with ILM (Index Lifecycle Management) policies. Hot → Warm → Cold (S3) transitions automated.

```
Day 0 (today): logs-2026.03.14
    │
    ▼
Hot phase (days 0–7):
  SSD-backed data nodes (3 replicas for durability + read throughput)
  Full indexing: all fields analyzed, keyword + text mappings
  Refresh interval: 1s (near real-time search)
  Shard count: 6 primary × 1 replica = 12 shards per daily index
    │
    ▼ ILM rolls after 7 days
Warm phase (days 7–30):
  HDD-backed warm nodes (1 replica — cheaper)
  Force-merge to 1 segment per shard (optimize read, no more writes)
  Read-only index (no new docs)
  Shrink from 6 to 2 primary shards (fewer shards = less overhead)
    │
    ▼ ILM rolls after 30 days
Cold phase (days 30–365):
  Snapshot to S3 (compressed, ~10:1 ratio)
  Delete from OpenSearch cluster
  Searchable on demand: restore snapshot to warm nodes (~5 min for 1 day)
    │
    ▼ After 365 days
Delete permanently (or move to Glacier for compliance holds)
```

Shard sizing: target 30–50 GB per shard. 2 TB/day ÷ 6 shards = ~333 GB/shard raw, but with compression ~33 GB/shard. Within target.

Multi-tenant index isolation: all orgs share the same daily index (not per-org indices — 500 orgs × 365 days = 182K indices would kill cluster state). Tenant isolation via org_id field + filtered aliases. Every query has `"filter": {"term": {"org_id": "ORG-4201"}}` injected server-side.

Query routing: Search Service inspects time range → selects which daily indices to query. Last 1h = today's index only (1 hot index). Last 7d = 7 hot indices. Last 30d = 7 hot + 23 warm. This keeps 80% of queries hitting only fast SSD nodes.

```
Search time range → index selection:

  "last 1h"  → logs-2026.03.14 (1 hot index, SSD)         < 100ms
  "last 24h" → logs-2026.03.14, logs-2026.03.13 (2 hot)   < 200ms
  "last 7d"  → 7 hot indices                                < 500ms
  "last 30d" → 7 hot + 23 warm indices                      < 2s
  "last 90d" → restore cold snapshots first, then query      ~5 min setup
```

Trade-off: shared indices mean a noisy tenant (high-volume org) can impact shard performance for others. Mitigate with per-org ingestion rate limits and dedicated hot shards for Enterprise-tier orgs.

> "Daily indices with ILM: 7d hot on SSD (1s refresh), 30d warm on HDD (read-only, merged), 1yr cold on S3 (10:1 compression). 80% of queries hit only hot tier — p95 < 500ms."

---

### Deep Dive 3: Live Tail — Real-Time Log Streaming via WebSocket

**Problem:** Engineers debugging a live issue need to see logs as they arrive, filtered by service/level/keyword. 5K concurrent tail sessions, each with different filters. Can't query OpenSearch repeatedly (too slow, too expensive).

**Approach:** Tail Fanout Service consumes from Kafka "processed-logs" and evaluates each event against active tail subscriptions in-memory. Matching events are pushed directly to WebSocket clients.

```
Client opens: WS /api/v1/tail?source=SRC-88201&filter=level:ERROR
    │
    ▼
Tail Fanout Service:
  registers subscription: { clientId, org_id, source_id, filter_ast }
  compiles filter into fast in-memory predicate
    │
    ▼
Kafka consumer (all partitions of "processed-logs"):
  for each event:
    for each active subscription matching org_id:
      evaluate filter predicate against event fields
      match? → push to client's WebSocket connection
```

Filter compilation: parse `level:ERROR AND service:payment*` into an AST at subscription time. Evaluate per-event is O(fields × filter_terms) — fast for typical filters (3–5 terms). Wildcard `payment*` compiled to prefix match.

Scaling: each Tail Fanout pod handles ~1K WebSocket connections. 5K connections = 5 pods. Each pod consumes ALL Kafka partitions (broadcast consumer group — each pod sees every event). This is intentional: a tail subscription can match events from any partition.

Why not use OpenSearch polling? At 5K clients polling every 1s = 5K search QPS — would overwhelm the cluster. Kafka-based push is O(1) per event per matching subscription, regardless of how many non-matching events flow through.

Backpressure for slow clients: if a WebSocket client can't keep up (slow network), buffer up to 1000 events in a ring buffer. If buffer overflows, drop oldest events and send a `{"type":"gap","dropped":42}` marker so the client knows it missed some.

```
Event throughput per Tail pod:
  50K events/sec from Kafka × 1K subscriptions = 50M filter evaluations/sec
  Each evaluation: ~500ns (compiled predicate, field lookup)
  Total CPU: 50M × 500ns = 25ms/sec per core → easily fits on 4-core pod
```

Disconnection: when client disconnects, remove subscription from in-memory registry. No persistent state — if pod restarts, clients reconnect and re-register.

Trade-off: broadcast consumer means every pod processes every event, even if no subscriptions match. At 50K events/sec this is fine. At 500K events/sec, partition the subscriptions by org_id and assign Kafka partitions accordingly.

> "Kafka-based push, not OpenSearch polling — filter compiled to in-memory predicate at subscription time, 50K events/sec × 1K subscriptions = 25ms CPU/sec. Ring buffer drops oldest on slow clients."

---

### Deep Dive 4: Alert Evaluator — Sliding Window Counters at Scale

**Problem:** 10K active alert rules, each with a query like `level:ERROR AND service:payment*` and a condition like `count > 50 in 5 minutes`. Must evaluate against 50K events/sec stream with < 30s detection latency. Can't run 10K OpenSearch queries every minute.

**Approach:** Stream-side evaluation. Alert Evaluator consumes from Kafka "processed-logs", matches each event against all active alert rules, and maintains sliding window counters in Redis.

```
Alert rule loaded into memory:
  ALR-301: query_predicate = (level == ERROR AND service matches "payment*")
           condition = count > 50
           window = 5 min
           cooldown = 15 min
    │
    ▼
For each event from Kafka "processed-logs":
  for each alert rule in same org_id:
    evaluate query_predicate against event
    match? → INCR sliding window counter in Redis
    │
    ▼
Redis sliding window (implemented as sorted set):
  key: "alert_window:{alert_id}"
  members: event timestamps (score = epoch_ms)
  on each increment:
    ZADD alert_window:ALR-301 {now_ms} {event_id}
    ZREMRANGEBYSCORE alert_window:ALR-301 -inf {now_ms - 300000}  ← prune older than 5min
    ZCARD alert_window:ALR-301 → current_count
    │
    ▼
current_count > threshold (50)?
  Yes → check cooldown: GET "alert_cooldown:ALR-301"
        exists? → skip (already fired recently)
        not exists? → FIRE ALERT
          publish ALERT_TRIGGER to Kafka "alerts"
          SET "alert_cooldown:ALR-301" EX 900  ← 15min cooldown TTL
  No  → continue
```

Why Redis ZSET for sliding windows? True sliding window (not tumbling). ZADD + ZREMRANGEBYSCORE + ZCARD in a pipeline = 3 commands, < 1ms. Survives pod restarts (state in Redis, not in-process memory).

Rule hot-reload: Alert Evaluator polls PostgreSQL every 30s for rule changes. New/updated rules compiled to predicates and swapped in. No restart needed.

Scaling: 10K rules × 50K events/sec = 500M predicate evaluations/sec worst case. But most events match 0 rules (wrong org or wrong level). Optimization: group rules by org_id, only evaluate rules for the event's org. Average org has 20 rules → 50K × 20 = 1M evaluations/sec. At ~500ns each = 0.5s CPU/sec → 1 pod handles it.

Alert delivery: same pattern as uptime monitoring — look up channels, respect cooldowns, retry with backoff, digest for rate-limited users.

Trade-off: Redis ZSET per alert rule. 10K rules × avg 50 members = 500K Redis keys. ~50 MB memory. Trivial.

> "Stream-side evaluation — each event checked against org's alert rules in-memory, Redis ZSET sliding windows for true rolling counts, cooldown TTL prevents alert storms. 10K rules at 50K events/sec on a single pod."

---

### Deep Dive 5: PII Masking & Compliance — Field-Level Security

**Problem:** Logs often contain PII (emails, IPs, credit card numbers, auth tokens). GDPR requires masking or redaction. Different roles within an org should see different fields (SOC team sees IPs, developers don't). Must be done at ingestion time (can't un-index PII) and at query time (RBAC).

**Approach:** Two layers. Layer 1: regex-based masking in Stream Processor (irreversible, at write time). Layer 2: field-level security in OpenSearch (reversible, at read time, role-based).

```
Layer 1 — Write-Time Masking (Stream Processor):

  Raw event: { "message": "User [email] failed login from 10.0.1.5" }
      │
      ▼
  PII scanner (regex patterns, configurable per org):
    email:       [a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}
    credit card: \b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b
    auth token:  Bearer [A-Za-z0-9\-._~+/]+=*
    SSN:         \b\d{3}-\d{2}-\d{4}\b
      │
      ▼
  Masked event: { "message": "User [REDACTED_EMAIL] failed login from 10.0.1.5" }
  Original value hashed and stored in separate audit index (access-controlled)
```

```
Layer 2 — Read-Time Field Security (OpenSearch):

  Role: "developer"
    → can see: timestamp, level, service, message (masked), trace_id
    → cannot see: source_ip, user_id, raw_headers

  Role: "security-analyst"
    → can see: all fields including source_ip, user_id
    → cannot see: raw auth tokens (always masked at write time)

  Implementation: OpenSearch Security plugin (document-level + field-level security)
    each role maps to a set of allowed/denied fields
    query results automatically strip denied fields before returning
```

Org-configurable masking rules: each org can define custom regex patterns via API. Stored in PostgreSQL, cached in Redis, loaded by Stream Processor on startup + every 30s refresh.

Audit trail: every search query logged with user_id, query text, timestamp, result count. Stored in a separate OpenSearch index with 2yr retention. Immutable (append-only, no deletes).

GDPR right-to-erasure: logs are immutable, but PII is already masked at write time. For the audit hash index, a deletion job can purge entries matching a user identifier. Compliance team triggers via admin API.

Trade-off: write-time masking is irreversible — if a regex is too aggressive, you lose data. Mitigate with a "dry run" mode that logs what would be masked before enabling. Also keep raw events in Kafka for 24h — if masking rules are wrong, reprocess from raw-logs topic with corrected rules.

> "Two-layer PII protection — irreversible regex masking at write time in Stream Processor, reversible field-level RBAC at read time in OpenSearch. Audit trail for every query. 24h Kafka raw retention allows reprocessing if masking rules need correction."

