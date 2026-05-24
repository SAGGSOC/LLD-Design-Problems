# Production HLD — Sentry-Style Error Aggregation Platform

A reference design based on how Sentry (open-source + commercial) actually runs in production. Numbers here are typical for a mid-to-large scale deployment (~1M events/sec, ~100M daily events, ~10K customer orgs). This is the design shape used by Sentry, Airbrake, Rollbar, and most error-tracking SaaS platforms.

---

## 1. Problem Statement

> "Collect exceptions and errors from thousands of services in real-time. Group similar errors together. Show engineers a prioritized list of what's broken, with stack traces and context. Support queries like 'what's the most frequent error in the last hour for service X?' in < 200ms. Must handle 1M events/sec peak."

The problem has the same shape as a top-K exception dashboard, but with a harder grouping problem (fingerprinting), much higher cardinality, and strict multi-tenancy.

---

## 2. Scale Targets (typical production)

| Dimension | Target |
|---|---|
| Ingestion throughput | 1M events/sec peak, 200K/sec steady |
| Daily events | 100M - 10B depending on tier |
| Distinct event groups ("issues") | 100M+ across all tenants |
| Query p99 (top-K within a tenant) | < 200ms |
| Query p99 (issue detail view) | < 500ms |
| Ingestion → visible latency | < 30 seconds |
| Hot retention | 30-90 days |
| Cold retention | 1 year |
| Availability | 99.95% |
| Multi-tenancy | 10K+ customer orgs, data isolation required |

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       Client SDKs                                │
│           (Python, JS, Java, Go, Rust, mobile)                   │
│                                                                  │
│  Capture exception → serialize → batch → POST /envelope/         │
│  Local buffering with bounded memory + disk                      │
│  Sampling controls (session-based, rate-based)                  │
└───────────────────────────┬─────────────────────────────────────┘
                            │ HTTPS
                            ▼
        ┌───────────────────────────────────────┐
        │          Relay Layer (Rust)           │
        │     Public-facing edge ingestor       │
        │                                       │
        │  • TLS termination                    │
        │  • Rate limiting per DSN (API key)    │
        │  • PII scrubbing                      │
        │  • Quota enforcement (org-level)      │
        │  • Geo-routing                        │
        │  • Envelope forward to Kafka          │
        └──────────────────┬────────────────────┘
                           │
                           ▼
        ┌───────────────────────────────────────┐
        │               Kafka                    │
        │                                       │
        │  Topic: events                        │
        │    partition by: org_id hash          │
        │    replication: 3                     │
        │    retention: 72h (for replay)        │
        │  Topic: transactions                  │
        │  Topic: attachments                   │
        └──────────────────┬────────────────────┘
                           │
        ┌──────────────────┼───────────────────┐
        │                  │                   │
        ▼                  ▼                   ▼
┌──────────────┐  ┌──────────────┐   ┌──────────────┐
│   Ingest     │  │   Snuba      │   │   Attachment │
│  Consumer    │  │  Consumer    │   │   Consumer   │
│              │  │              │   │              │
│ (symbolicate,│  │ (ClickHouse  │   │ (blob store) │
│  group,      │  │  materializer│   │              │
│  save metadata)│  │              │   │              │
└──────┬───────┘  └──────┬───────┘   └──────┬───────┘
       │                 │                   │
       ▼                 ▼                   ▼
┌──────────────┐  ┌──────────────┐   ┌──────────────┐
│  PostgreSQL  │  │  ClickHouse  │   │  S3 / GCS    │
│              │  │              │   │              │
│ Issue meta,  │  │ Event data   │   │ Minidumps,   │
│ users,       │  │ Time-series  │   │ source maps, │
│ projects,    │  │ counts,      │   │ raw payloads │
│ orgs,        │  │ tags, search │   │              │
│ alert rules  │  │              │   │              │
└──────────────┘  └──────────────┘   └──────────────┘

                           │
                           ▼
                  ┌──────────────────┐
                  │    API Layer      │
                  │ (Django / Python) │
                  │                   │
                  │ /api/0/projects/  │
                  │   {org}/{proj}/   │
                  │   issues/         │
                  └──────────┬───────┘
                             │
                             ▼
                  ┌──────────────────┐
                  │    Web UI         │
                  │  (React SPA)      │
                  └──────────────────┘
```

---

## 4. The Data Pipeline Breakdown

### 4.1 Client SDK

The SDK runs inside every customer's app. Production-grade concerns here:

- **Bounded in-memory queue**, default 30 events, drops oldest on overflow
- **Local disk spillover** on network failure (up to ~10MB per service)
- **Envelope format** — binary wire protocol that bundles event + attachments + checksums
- **Transport** — HTTP/2, Keep-Alive, exponential backoff on 5xx, never retries on 4xx
- **Sampling** — probabilistic (0.1-100%), session-based, rate-limited
- **Context capture** — breadcrumbs, user, tags, environment, release version, runtime info
- **Transport isolation** — SDK never throws; failure to send must never affect the host app

### 4.2 Relay

Relay is an edge ingestor written in Rust (for performance and memory safety). Key responsibilities:

- **Auth**: validate DSN (Data Source Name — the public API key) against org/project
- **Rate limit**: leaky-bucket per DSN, returns 429 when exceeded; SDK backs off
- **Quota enforcement**: each org has a monthly event quota; reject when exceeded
- **PII scrubbing**: regex-based and field-based redaction (credit cards, emails, IPs) BEFORE data leaves the ingestion edge — regulatory requirement (GDPR, PCI)
- **Envelope validation**: reject malformed payloads at the edge to protect downstream
- **Forward to Kafka**: write envelope to `events` topic, partitioned by `org_id`

Why Rust: handles 100K+ req/sec per instance on commodity hardware, sub-millisecond latency, low memory footprint. Critical at the edge.

### 4.3 Kafka

Partition key design is the most important decision here.

- **Partitioned by `org_id` hash**: all events from one org go to the same partition, enabling ordered processing per tenant
- **32-128 partitions per topic**: scaled with throughput
- **3x replication**: data durability
- **72h retention**: enables stream replay during outages or schema changes
- **Compacted or time-based**: events topic is time-based, config topic is log-compacted

### 4.4 Ingest Consumer

This is a Python/Celery worker cluster. It's where the most interesting work happens.

```
For each event from Kafka:
  1. Symbolicate (resolve stack trace frames)
     - For JS: apply source maps to translate minified line:col to original
     - For native (iOS/Android): resolve debug symbols from stored debug files
  2. Process
     - Canonicalize stack trace (strip node_modules, normalize paths)
     - Compute group fingerprint
  3. Group ("issue creation")
     - Query: is there an existing issue with this fingerprint?
     - Yes → increment that issue's event count, update last_seen, check alert rules
     - No  → create new issue in Postgres, publish "new issue" event
  4. Write metadata to Postgres
     - issue_id, project_id, first_seen, last_seen, count, culprit, level
  5. Forward to Snuba consumer (ClickHouse ingestion)
  6. Trigger alert rule evaluation if conditions met
```

### 4.5 Snuba (ClickHouse)

Snuba is a dedicated service that sits in front of ClickHouse. It's how Sentry solves the analytics query problem at scale.

- **ClickHouse** for time-series analytics — columnar, distributed, optimized for aggregation queries
- **Snuba** provides a SQL-like query API, query caching, and materialized view management
- **Tables**: `events`, `errors`, `transactions`, `outcomes` — each with pre-aggregated rollups
- **Rollups** precomputed at 10s, 1m, 1h, 1d granularity for dashboards
- **Queries that hit Snuba**:
  - "top 50 issues by event count for org X in last 24h"
  - "event count over time for issue Y, bucketed by hour"
  - "distinct users affected by issue Y in last 7 days"

Why separate from Postgres: Postgres handles relational metadata (issues, projects, users), but time-series event counts at 1M/sec need a column store.

### 4.6 Postgres

Source of truth for everything relational:

- **Orgs, projects, users, teams** — multi-tenant boundary
- **Issues** — grouped exceptions, one row per fingerprint
- **Alert rules, integrations, notification settings**
- **Release tracking, deploy markers**
- **Saved queries, dashboards**

At the scale of "100M issues", Postgres is sharded by org_id (Sentry uses Citus in the commercial version; open-source uses a single PG with aggressive partitioning).

### 4.7 Object Storage (S3)

Raw event payloads, source maps, minidumps, large attachments. ClickHouse holds a compact row for querying; S3 holds the full JSON for display. Cold storage uses lifecycle policies (S3 IA → Glacier).

---

## 5. Query Path (the read side)

The UI makes dozens of calls when you load the issues list:

```
GET /api/0/organizations/{org}/issues/?project=123&statsPeriod=24h&sort=freq
     │
     ▼
Django API:
  1. Check auth (session cookie or API token)
  2. Verify org membership
  3. Query Snuba:
     SELECT issue_id, count() FROM events
     WHERE project_id = 123 AND timestamp > now() - 24h
     GROUP BY issue_id ORDER BY count() DESC LIMIT 25
  4. Enrich with Postgres metadata:
     SELECT id, title, culprit, first_seen, level FROM issues
     WHERE id IN (...)
  5. Merge, paginate, return JSON
```

Query latency breakdown:
- Auth check: 1-2ms (Redis cache)
- Snuba query: 20-80ms (ClickHouse aggregation)
- Postgres enrichment: 5-15ms (indexed lookup)
- Serialization: 2-5ms
- **Total**: ~30-100ms p50, ~200ms p99

---

## 6. Alert Evaluation (real-time triggers)

Real-time alerting is the feature that justifies the whole pipeline for customers.

```
Ingest consumer finishes processing an event
  │
  ▼
Check: does this event match any alert rule for this project?
  (Rules are cached in Redis, refreshed every 30s)
  │
  ▼
If match:
  - Evaluate condition (e.g., "more than 100 events in 5 min")
     Use Redis sorted set with timestamps as scores for windowed counts
  - If threshold crossed, enqueue notification job
     (email, Slack, PagerDuty, webhook)
  - Write to alert firing log
```

**Rate limiting alerts** is critical: a noisy issue would otherwise spam Slack with thousands of messages. Mute individual issues for X minutes after first alert, group alerts by issue+time-window.

---

## 7. Scaling Decisions

### Horizontal scaling approach

| Layer | Scales by |
|---|---|
| Relay | Add replicas behind LB, stateless |
| Kafka | Add partitions + brokers |
| Ingest consumers | Add replicas, Kafka consumer group balances partitions |
| Snuba/ClickHouse | Add shards + replicas |
| Postgres | Connection pooling (pgbouncer), read replicas, eventually Citus sharding |
| Redis | Add shards for quota/rate-limit state |

### Back-pressure design

When ingestion spikes:

1. Kafka absorbs — 72h retention means consumers can lag
2. Consumer lag triggers auto-scaling of worker pool
3. If quota exceeded: Relay returns 429 at the edge, SDK backs off locally
4. If infrastructure saturated: Relay drops low-priority events (sampled down) based on org tier

The key insight: **never block ingestion on downstream slowness**. The SDK → Relay → Kafka path must stay fast even if Snuba or Postgres is slow. Kafka is the shock absorber.

---

## 8. Multi-Tenancy Considerations

```
┌──────────────────────────────────────────────────────────────────┐
│ Data isolation:                                                   │
│   Every row in every table has org_id                            │
│   Every query filters by org_id (enforced at API layer)          │
│   Postgres: org_id is a leading index column on all tables       │
│   ClickHouse: org_id is part of the sort key                     │
│                                                                  │
│ Noisy neighbor mitigation:                                       │
│   Per-org Kafka partition guarantees one bad org can't stall     │
│     processing for others (rebalance + isolated consumer groups) │
│   Per-DSN rate limits at Relay                                   │
│   Per-org query cost tracking — kill long-running queries        │
│   Separate Snuba clusters for enterprise vs free tier            │
│                                                                  │
│ Billing:                                                         │
│   Outcomes topic: every ingested event → outcome row             │
│   (accepted, filtered, rate-limited, quota-exceeded)             │
│   Billing aggregates daily from ClickHouse                       │
└──────────────────────────────────────────────────────────────────┘
```

---

## 9. Failure Modes and Mitigations

| Failure | Impact | Mitigation |
|---|---|---|
| Relay down in one region | Ingestion drops in that region | Global load balancer, SDKs have fallback URLs |
| Kafka primary AZ lost | Temporary ingestion pause | Multi-AZ replication, 3x replicas, producer retries |
| Ingest consumer lag grows | Issues appear late but no data loss | 72h retention gives replay window; auto-scale consumers |
| ClickHouse slow query | Dashboard loads slowly | Snuba circuit-breaker returns partial results, query caching |
| Postgres write load spike | Issue creation delays | Write buffering, async write queue, degrade to "issue creation batched to 10s intervals" |
| Large customer onboarding | Quota ramps can saturate shards | Gradual quota increase, dedicated infra for top-tier customers |
| Bad deploy of ingest code | Data processing stops | Canary rollout, Kafka gives replay ability post-rollback |

---

## 10. What Makes This a Production System vs an Interview Answer

Interview answers often stop at "Kafka → Flink → Redis". Production adds:

1. **The edge layer (Relay)** exists specifically to reject bad traffic before it hits expensive components. You can't afford to run auth + PII scrubbing in every consumer.

2. **Three distinct stores, each chosen for its strength**:
   - Postgres for relational metadata (issues, users)
   - ClickHouse for time-series analytics
   - S3 for large blobs
   If you tried to put everything in one DB, you'd fail on one axis.

3. **Symbolic resolution is its own service**. Source maps and debug symbols are huge. Downloading them on every request is expensive. Symbolicator runs as a dedicated service with its own cache of resolved symbols.

4. **The "outcomes" topic** — a per-event audit log separate from the event data. Critical for billing, quota enforcement, and understanding drop patterns. Often forgotten in interviews.

5. **SDK reliability requirements**. The SDK running inside a customer's production app must never crash that app, even if the ingestion backend is completely down. This drives the local buffering, silent-fail design, and bounded memory constraints.

6. **Symbol of replay capability**. Kafka's 72h retention isn't just for outages — it's for schema migrations, backfills, and recovering from data corruption. "We can replay" is a feature.

7. **Separation of concerns across 10+ services**. In practice each arrow in the diagram is a team with on-call rotation. Service boundaries follow team boundaries.

---

## 11. Numbers That Interviewers Ask About

If they ask for sizing, here's what to say:

### Ingestion

```
1M events/sec peak × 3KB avg event size = 3 GB/sec network
That's 258 TB/day RAW — way too much to store directly.

Mitigations:
- Relay compresses (gzip/zstd), ~5x reduction → 600 MB/sec ≈ 50 TB/day
- Sampling: free tier samples at 10%, enterprise at 100%
- Quota: most events rejected before they hit Kafka
- Net stored: ~5-10 TB/day in hot path (ClickHouse), 20-30 TB/day in S3
```

### ClickHouse sizing

```
100M issues × ~1KB row = 100 GB for issue metadata (Postgres)
100M events/day × 500 bytes columnar = 50 GB/day → 1.5 TB for 30-day hot
ClickHouse compression ~10x → 150 GB actual storage for hot window
Plus 10+ precomputed rollup tables → 300-500 GB total

Cluster: 10-20 nodes, NVMe SSDs, 256GB RAM each
```

### Kafka sizing

```
Events topic: 1M msg/sec × 3KB = 3 GB/sec ingress
72h retention × 3 GB/sec = ~750 TB raw (with compression, ~150 TB)
Spread across 30+ partitions, 3x replication
Cluster: 20+ brokers, ~10 TB disk each
```

---

## 12. The Interview Short-Form

If you have 15 seconds to explain the shape:

> "SDK in the client app batches errors and sends to an edge ingestor called Relay. Relay handles auth, rate limiting, and PII scrubbing, then writes envelopes to Kafka partitioned by org. An ingest consumer symbolicates stack traces, computes a group fingerprint, and either updates or creates an issue row in Postgres. A separate consumer writes time-series event data to ClickHouse via a service called Snuba. The web UI queries the API which joins Postgres metadata with ClickHouse counts to render the issues list. Alert rules evaluate on the ingest path and fire notifications through side channels. Kafka provides 72h of replay for failures. The whole thing is multi-tenant with per-org quotas enforced at the edge."

That paragraph hits 90% of what a senior-level HLD interviewer is looking for. Everything else is drilling into a specific component.
