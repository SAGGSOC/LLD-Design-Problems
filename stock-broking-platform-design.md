# Stock Broking Platform — System Design (Zerodha-like)

---

## 1. Functional Requirements

- `placeOrder(userId, symbol, qty, price, type, side)` — place buy/sell order (market/limit)
- `cancelOrder(userId, orderId)` — cancel a pending order
- `getPortfolio(userId)` — view current holdings with live P&L
- `getOrderBook(userId)` — view all orders (open, executed, cancelled)
- `getMarketData(symbol)` — real-time price, OHLC, depth (bid/ask)
- `getWatchlist(userId)` — user's watchlist with live prices
- `addFunds(userId, amount)` / `withdrawFunds(userId, amount)` — manage balance

### Clarifying Questions
- "Concurrent users during market hours?" → 1-2M active, 10M registered
- "Order types?" → Market, Limit, Stop-Loss, Stop-Loss Market
- "Asset classes?" → Equities first, F&O as extension
- "Real exchange connection?" → Yes, FIX protocol to NSE/BSE
- "Real-time prices?" → Yes, WebSocket push to clients

---

## 2. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Registered users | 10M+ |
| Concurrent users (9:15 AM - 3:30 PM) | 1-2M |
| Orders/sec (peak at market open) | 50K-100K |
| Market data ticks | ~5000 symbols x 1 tick/sec |
| Order placement latency (broker side) | < 50ms |
| Market data latency (exchange to screen) | < 100ms |
| Portfolio query latency | < 200ms |
| Availability (market hours) | 99.99% |
| Consistency (orders & funds) | Strong |
| Durability | Zero order loss |


---

## 3. Core Entities

**User:**
```
userId (PK) | name   | email   | pan          | demat_id   | status
"u42"       | "..."  | "..."   | "ABCDE1234F" | "IN302..." | "active"
```

**Funds / Ledger:**
```
userId (PK) | available_balance | blocked_margin | total_deposited
"u42"       | 50000.00          | 12000.00       | 100000.00
```

**Order:**
```
orderId (PK) | userId | symbol | side | type   | qty | price  | status     | exchange_order_id | created_at
"ord-1001"   | "u42"  | "INFY" | BUY  | LIMIT  | 50  | 1450.0 | EXECUTED   | "NSE-98765"       | 2026-03-13T09:30:00
```

**Holding (Portfolio):**
```
userId (PK) | symbol (SK) | qty | avg_price | current_price | pnl
"u42"       | "INFY"      | 100 | 1420.50   | 1455.00       | +3450.00
```

**Trade (Execution):**
```
tradeId (PK) | orderId | symbol | side | qty | price  | exchange_trade_id | executed_at
"t-5001"     | "ord-1001" | "INFY" | BUY | 50 | 1450.0 | "NSE-T-12345"  | 2026-03-13T09:30:01
```

**Watchlist:**
```
userId (PK) | watchlist_name (SK) | symbols
"u42"       | "default"           | ["INFY", "TCS", "RELIANCE", "HDFCBANK"]
```

### Entity Relationships
```
User ──1:N──▶ Orders ──1:N──▶ Trades
User ──1:N──▶ Holdings
User ──1:1──▶ Funds/Ledger
User ──1:N──▶ Watchlists
```

---

## 4. API Routes

```
POST   /orders
  Body: { "symbol": "INFY", "side": "BUY", "type": "LIMIT",
          "qty": 50, "price": 1450.00 }
  Response: { "orderId": "ord-1001", "status": "PENDING", "timestamp": "..." }
  Errors: 400 (invalid params), 403 (insufficient margin), 429 (rate limited)

DELETE /orders/{orderId}
  Response: { "orderId": "ord-1001", "status": "CANCELLED" }
  Errors: 404 (not found), 409 (already executed)

GET    /orders?status=OPEN&page=1&limit=20
  Response: { "orders": [...], "total": 45, "page": 1 }

GET    /portfolio
  Response: { "holdings": [
    { "symbol": "INFY", "qty": 100, "avgPrice": 1420.50,
      "currentPrice": 1455.00, "pnl": 3450.00, "pnlPercent": 2.43 }
  ], "totalInvestment": 142050, "currentValue": 145500 }

GET    /market/quote/{symbol}
  Response: { "symbol": "INFY", "ltp": 1455.00, "open": 1440,
              "high": 1462, "low": 1435, "volume": 2340000,
              "bidDepth": [...], "askDepth": [...] }

WS     /market/stream?symbols=INFY,TCS,RELIANCE
  Push: { "symbol": "INFY", "ltp": 1455.50, "volume": 2341000, "ts": "..." }

POST   /funds/add
  Body: { "amount": 50000, "paymentMethod": "UPI" }
  Response: { "transactionId": "txn-001", "newBalance": 100000 }

POST   /funds/withdraw
  Body: { "amount": 20000, "bankAccount": "..." }
  Response: { "transactionId": "txn-002", "status": "PROCESSING" }

GET    /watchlist
  Response: { "watchlists": [{ "name": "default", "symbols": [...] }] }
```

---

## 5. High-Level Design

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────────────────────┐
│  Mobile /    │     │   API Gateway     │     │      Application Layer       │
│  Web Client  │────▶│   (Auth, Rate     │────▶│                              │
│              │     │    Limiting)      │     │  ┌────────────────────────┐  │
└──────┬───────┘     └──────────────────┘     │  │   Order Service        │  │
       │                                       │  │   (validate, margin    │  │
       │  WebSocket                            │  │    check, route)       │  │
       │                                       │  └───────────┬────────────┘  │
       │         ┌──────────────────┐          │              │               │
       └────────▶│  Market Data     │          │  ┌───────────▼────────────┐  │
                 │  Streaming       │          │  │   Exchange Gateway     │  │
                 │  Service (WS)    │          │  │   (FIX Protocol to     │  │
                 └────────┬─────────┘          │  │    NSE/BSE)            │  │
                          │                    │  └───────────┬────────────┘  │
                          │                    │              │               │
                          │                    │  ┌───────────▼────────────┐  │
                          │                    │  │   Portfolio Service    │  │
                          │                    │  │   (holdings, P&L)     │  │
                          │                    │  └────────────────────────┘  │
                          │                    │                              │
                          │                    │  ┌────────────────────────┐  │
                          │                    │  │   Funds Service        │  │
                          │                    │  │   (margin, ledger)     │  │
                          │                    │  └────────────────────────┘  │
                          │                    └──────────────────────────────┘
                          │
          ┌───────────────┼──────────────────────────────────┐
          │               │                                  │
          ▼               ▼                                  ▼
   ┌─────────────┐ ┌──────────────┐  ┌──────────────┐ ┌──────────────┐
   │  Redis      │ │  Order DB    │  │  Holdings DB │ │  Kafka       │
   │  (Market    │ │  (Postgres)  │  │  (Postgres)  │ │  (Event Bus) │
   │   Data      │ │              │  │              │ │              │
   │   Cache)    │ │              │  │              │ │              │
   └─────────────┘ └──────────────┘  └──────────────┘ └──────────────┘
                                                              │
                                                    ┌─────────┼─────────┐
                                                    ▼         ▼         ▼
                                              ┌─────────┐ ┌───────┐ ┌────────┐
                                              │Notif.   │ │Audit  │ │Risk    │
                                              │Service  │ │Logger │ │Engine  │
                                              └─────────┘ └───────┘ └────────┘
```

### Component Responsibilities

| Component | Role |
|---|---|
| API Gateway | Auth (JWT/OAuth), rate limiting, TLS termination |
| Order Service | Validate order params, check margin, persist order, route to exchange |
| Exchange Gateway | Translate orders to FIX protocol, send to NSE/BSE, receive fills |
| Market Data Streaming | Receive ticks from exchange, fan-out to clients via WebSocket |
| Portfolio Service | Compute holdings, live P&L using current market prices |
| Funds Service | Margin management, ledger, payment gateway integration |
| Redis | Market data cache (LTP, depth), session cache |
| Order DB (Postgres) | Orders, trades — strong consistency, ACID transactions |
| Holdings DB (Postgres) | Portfolio positions — updated on trade execution |
| Kafka | Event bus for async processing (notifications, audit, risk) |

---

## 6. Deep Dive 1: Order Lifecycle & Exchange Integration

### Order State Machine

```
                    ┌──────────┐
         place      │          │    exchange rejects
  User ───────────▶ │ PENDING  │ ──────────────────▶ REJECTED
                    │          │
                    └────┬─────┘
                         │
                    validate + margin block
                         │
                    ┌────▼─────┐
                    │  OPEN    │ ◀──── partial fill loops back
                    │ (at      │
                    │ exchange) │
                    └────┬─────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
         full fill   partial   user cancels
              │       fill         │
              ▼          │         ▼
        ┌──────────┐     │   ┌───────────┐
        │ EXECUTED │     │   │ CANCELLED │
        └──────────┘     │   └───────────┘
                         ▼
                   ┌───────────┐
                   │ PARTIALLY │
                   │ EXECUTED  │
                   └───────────┘
```

### Order Placement Flow (Critical Path)

```
Client → API Gateway → Order Service:

1. VALIDATE: Check symbol exists, qty > 0, price valid for order type
2. MARGIN CHECK (synchronous, must be atomic):
   BEGIN TRANSACTION
     available = SELECT available_balance FROM funds WHERE userId = ? FOR UPDATE
     required_margin = qty * price  (simplified; real margin = SPAN + exposure)
     IF available < required_margin → REJECT "Insufficient margin"
     UPDATE funds SET available_balance -= required_margin,
                      blocked_margin += required_margin
     INSERT INTO orders (orderId, userId, symbol, ..., status='PENDING')
   COMMIT
3. SEND TO EXCHANGE: Exchange Gateway converts to FIX message
   FIX NewOrderSingle → NSE
4. EXCHANGE ACK: NSE returns order confirmation
   UPDATE orders SET status = 'OPEN', exchange_order_id = ?
5. RETURN to client: { orderId, status: "OPEN" }
```

### Why Postgres for Orders (Not NoSQL)?

| Reason | Detail |
|---|---|
| ACID transactions | Margin block + order insert must be atomic. Money is involved. |
| Complex queries | "Show me all LIMIT orders for INFY placed today" — SQL is natural |
| Consistency | Strong consistency required. No eventual consistency for financial data. |
| Joins | Order → Trades → Holdings chain needs relational joins |

### Exchange Gateway: FIX Protocol

```
Broker (us)                          Exchange (NSE)
    │                                     │
    │── FIX NewOrderSingle ──────────────▶│
    │   (ClOrdID, Symbol, Side,           │
    │    OrderQty, Price, OrdType)        │
    │                                     │
    │◀── FIX ExecutionReport ────────────│
    │   (ExecType=NEW, OrderID)           │  ← order accepted
    │                                     │
    │◀── FIX ExecutionReport ────────────│
    │   (ExecType=FILL, LastPx,           │  ← trade executed
    │    LastQty, CumQty)                 │
    │                                     │
```

FIX (Financial Information eXchange) is the industry standard. The Exchange Gateway maintains a persistent TCP session with the exchange, handles sequence numbers, heartbeats, and reconnection.


---

## 7. Deep Dive 2: Real-Time Market Data Pipeline

### The Challenge

- Exchange broadcasts ~5000 symbols, each ticking 1-5 times/sec
- 1-2M connected clients, each watching 5-30 symbols
- Total fan-out: potentially billions of messages/sec if naive

### Architecture: Tiered Fan-Out

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────────┐     ┌──────────────┐
│  Exchange    │────▶│  Market Data     │────▶│  Fan-Out Layer   │────▶│  WebSocket   │
│  Feed       │     │  Ingestion       │     │  (Pub/Sub)       │     │  Servers     │
│  (Multicast)│     │  Service         │     │                  │     │  (per-client │
└──────────────┘     └──────────────────┘     └──────────────────┘     │   filtering) │
                                                                       └──────┬───────┘
                                                                              │
                                                                              ▼
                                                                       ┌──────────────┐
                                                                       │  1-2M        │
                                                                       │  Clients     │
                                                                       └──────────────┘
```

**Step 1: Ingestion Service**
- Receives raw exchange feed (multicast UDP or TCP)
- Parses binary exchange format into normalized tick: `{symbol, ltp, bid, ask, volume, timestamp}`
- Writes to Redis for latest snapshot: `SET market:INFY '{"ltp":1455,"bid":1454.5,"ask":1455.5}'`
- Publishes to Kafka topic `market-ticks` (partitioned by symbol hash)

**Step 2: Fan-Out via Redis Pub/Sub or Kafka**

```python
# Ingestion publishes per-symbol channel
redis.publish("tick:INFY", json.dumps({"ltp": 1455.50, "vol": 2341000}))
redis.publish("tick:TCS",  json.dumps({"ltp": 3820.00, "vol": 1200000}))
```

**Step 3: WebSocket Servers subscribe and filter**

```python
# Each WS server subscribes to channels its connected clients care about
# Client connects: WS /market/stream?symbols=INFY,TCS

class MarketStreamHandler:
    def on_client_connect(self, client, symbols):
        for symbol in symbols:
            self.subscribe(f"tick:{symbol}", client)

    def on_tick(self, channel, message):
        # Push to all clients subscribed to this symbol on THIS server
        for client in self.subscribers[channel]:
            client.send(message)
```

### Scaling WebSocket Servers

```
                    ┌─────────────┐
                    │  Load       │
                    │  Balancer   │  (sticky sessions by userId)
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │  WS      │ │  WS      │ │  WS      │
        │  Server 1│ │  Server 2│ │  Server 3│
        │  ~300K   │ │  ~300K   │ │  ~300K   │
        │  conns   │ │  conns   │ │  conns   │
        └──────────┘ └──────────┘ └──────────┘
```

- Each WS server handles ~300K concurrent connections (epoll/kqueue)
- 1M clients ÷ 300K per server = 4 servers minimum (run 6-8 for headroom)
- Each server only subscribes to Redis channels for symbols its clients watch
- Deduplication: if 100K clients on Server 1 all watch INFY, Server 1 receives the tick ONCE and fans out locally

### Latency Budget: Exchange → Client Screen

```
Exchange tick → Ingestion parse: ~1-2ms
Ingestion → Redis Pub/Sub:      ~1ms
Redis → WS Server:              ~1ms
WS Server → Client (network):   ~10-50ms (depends on client location)
─────────────────────────────────────────
Total: ~15-55ms (well within 100ms target)
```

### Throttling for Slow Clients

```python
# Don't buffer unlimited ticks for slow mobile clients
class ClientConnection:
    def __init__(self):
        self.last_sent = {}  # symbol → timestamp
        self.min_interval = 200  # ms — max 5 updates/sec per symbol

    def maybe_send(self, symbol, tick):
        now = current_time_ms()
        if now - self.last_sent.get(symbol, 0) >= self.min_interval:
            self.send(tick)
            self.last_sent[symbol] = now
        # else: drop this tick for this client (they'll get the next one)
```

---

## 8. Deep Dive 3: Margin Management & Funds

### Why This Is Critical

If margin checking is wrong, the broker loses money. If a user places a BUY order for 10L worth of stock but only has 5L, and we let it through — the broker is on the hook.

### Margin Types

| Type | When | Calculation |
|---|---|---|
| Order margin | When order is placed | qty × price (simplified) |
| SPAN margin | For F&O positions | Exchange-defined risk-based margin |
| Exposure margin | Additional buffer | % of contract value |
| MTM margin | Intraday | Mark-to-market loss on open positions |

### Funds State Machine

```
┌─────────────────────────────────────────────────────┐
│                    User Funds                        │
│                                                      │
│  available_balance: 50,000                           │
│  blocked_margin:    12,000  (for open orders)        │
│  used_margin:       30,000  (for executed positions) │
│  total:            92,000                            │
└─────────────────────────────────────────────────────┘

Order placed (BUY 50 INFY @ 1450 = 72,500):
  IF available_balance >= 72,500 → NO, reject

Order placed (BUY 5 INFY @ 1450 = 7,250):
  available_balance: 50,000 - 7,250 = 42,750
  blocked_margin:    12,000 + 7,250 = 19,250

Order executed:
  blocked_margin:    19,250 - 7,250 = 12,000
  used_margin:       30,000 + 7,250 = 37,250
  (money moves from "blocked" to "used")

Order cancelled:
  blocked_margin:    19,250 - 7,250 = 12,000
  available_balance: 42,750 + 7,250 = 50,000
  (money returns to available)
```

### Atomic Margin Block (Postgres)

```sql
-- This MUST be a single transaction. No partial states.
BEGIN;

SELECT available_balance FROM funds
WHERE user_id = 'u42' FOR UPDATE;  -- row-level lock

-- Check
-- IF available < required → ROLLBACK and return error

UPDATE funds
SET available_balance = available_balance - 7250,
    blocked_margin = blocked_margin + 7250
WHERE user_id = 'u42';

INSERT INTO orders (order_id, user_id, symbol, side, type, qty, price, status)
VALUES ('ord-1001', 'u42', 'INFY', 'BUY', 'LIMIT', 5, 1450, 'PENDING');

COMMIT;
```

`FOR UPDATE` ensures no two concurrent orders can double-spend the same balance. This is the most critical transaction in the entire system.

### End-of-Day Settlement

```
3:30 PM: Market closes
3:30 - 5:00 PM: Settlement batch job runs

For each user:
  1. Calculate net obligations (buys - sells)
  2. Update holdings (add bought shares, remove sold shares)
  3. Release blocked margins for executed orders
  4. Debit/credit funds based on net settlement
  5. Apply brokerage charges, STT, GST, stamp duty
  6. Generate contract notes (PDF)
```

---

## 9. Deep Dive 4: Scaling for Market Open Spike

### The Problem

At 9:15 AM IST, the market opens. Within the first 60 seconds:
- 1-2M users log in simultaneously
- 50K-100K orders flood in
- Market data starts ticking for all 5000 symbols

This is a thundering herd problem.

### Strategy 1: Pre-Warm Everything

```
8:30 AM (45 min before market open):
  - Warm Redis caches with yesterday's closing prices
  - Pre-authenticate sessions (JWT tokens issued at login, not at 9:15)
  - Pre-load user watchlists into WS server memory
  - Establish FIX sessions with exchange
  - Scale up WS servers and order service pods (K8s HPA pre-scaling)
```

### Strategy 2: Order Queue with Back-Pressure

```
┌──────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Clients │────▶│  API Gateway │────▶│  Order Queue │────▶│  Order       │
│  (burst) │     │  (accepts    │     │  (Kafka /    │     │  Processor   │
│          │     │   all orders)│     │   in-memory) │     │  (rate-      │
└──────────┘     └──────────────┘     └──────────────┘     │   controlled)│
                                                            └──────────────┘
```

- API Gateway accepts orders immediately, returns `PENDING`
- Orders queue in Kafka (partitioned by userId for ordering guarantees)
- Order Processor consumes at a controlled rate matching exchange capacity
- Client polls or receives WebSocket push when order status changes

### Strategy 3: Connection Draining for WebSocket

```python
# Stagger WebSocket reconnections to avoid thundering herd
def on_client_reconnect(client):
    jitter = random.uniform(0, 5)  # 0-5 second random delay
    sleep(jitter)
    establish_connection(client)
```

### Auto-Scaling Profile

```
Time        WS Servers    Order Service Pods    DB Connections
8:00 AM     4             5                     50
9:00 AM     8             15                    150  (pre-scaled)
9:15 AM     8             15                    150  (absorbs spike)
10:00 AM    6             10                    100  (scale down)
3:30 PM     4             5                     50   (market close)
```

---

## 10. Deep Dive 5: Portfolio & Live P&L Computation

### The Challenge

When a user opens their portfolio, they want to see:
- Current holdings with quantities and average buy price
- Live P&L = (current_price - avg_price) × qty for each holding
- Total portfolio value updating in real-time

### Approach: Hybrid Compute

```
Static data (from DB):                  Dynamic data (from Redis):
┌────────────────────────┐              ┌────────────────────────┐
│ Holdings:              │              │ Market Data Cache:     │
│ INFY: qty=100,         │     merge    │ INFY: ltp=1455.00      │
│       avg_price=1420.50│ ──────────▶  │ TCS:  ltp=3820.00      │
│ TCS:  qty=50,          │              │ ...                    │
│       avg_price=3750.00│              └────────────────────────┘
└────────────────────────┘
                              ▼
                    ┌────────────────────────────────┐
                    │ Portfolio Response:             │
                    │ INFY: pnl = (1455-1420.5)×100  │
                    │           = +3,450              │
                    │ TCS:  pnl = (3820-3750)×50     │
                    │           = +3,500              │
                    │ Total P&L: +6,950              │
                    └────────────────────────────────┘
```

```python
def get_portfolio(user_id):
    # 1. Fetch holdings from DB (cacheable, changes only on trades)
    holdings = db.query("SELECT symbol, qty, avg_price FROM holdings WHERE user_id = ?", user_id)

    # 2. Batch fetch current prices from Redis
    symbols = [h.symbol for h in holdings]
    pipe = redis.pipeline()
    for s in symbols:
        pipe.hget(f"market:{s}", "ltp")
    prices = pipe.execute()

    # 3. Compute P&L in memory
    result = []
    total_investment = 0
    total_current = 0
    for h, current_price in zip(holdings, prices):
        current_price = float(current_price)
        pnl = (current_price - h.avg_price) * h.qty
        investment = h.avg_price * h.qty
        total_investment += investment
        total_current += current_price * h.qty
        result.append({
            "symbol": h.symbol,
            "qty": h.qty,
            "avgPrice": h.avg_price,
            "currentPrice": current_price,
            "pnl": round(pnl, 2),
            "pnlPercent": round((pnl / investment) * 100, 2)
        })

    return {
        "holdings": result,
        "totalInvestment": round(total_investment, 2),
        "currentValue": round(total_current, 2),
        "totalPnl": round(total_current - total_investment, 2)
    }
```

### Live P&L Updates via WebSocket

For users who keep the portfolio screen open, push P&L updates:

```python
# Server-side: when a tick arrives for a symbol in user's holdings
def on_tick_for_portfolio(user_id, symbol, new_price):
    holding = cache.get(f"holding:{user_id}:{symbol}")
    if holding:
        new_pnl = (new_price - holding.avg_price) * holding.qty
        ws_server.push(user_id, {
            "type": "PNL_UPDATE",
            "symbol": symbol,
            "currentPrice": new_price,
            "pnl": new_pnl
        })
```

---

## 11. Interview Walkthrough Pacing

| Phase | Time | What to Cover |
|---|---|---|
| Requirements | 3 min | Clarify: order types, asset classes, scale, real-time needs |
| Core Entities | 3 min | User, Order, Trade, Holding, Funds — show relationships |
| API Design | 3 min | REST for orders/portfolio, WebSocket for market data |
| HLD | 10 min | Draw architecture, explain Order Service → Exchange Gateway → FIX |
| Deep Dive 1 | 7 min | Order lifecycle, margin check transaction, FIX protocol |
| Deep Dive 2 | 7 min | Market data pipeline, WebSocket fan-out, latency budget |
| Deep Dive 3 | 5 min | Margin management, atomic funds, settlement |
| Trade-offs | 5 min | Postgres vs NoSQL for orders, sync vs async order routing |

---

## 12. Complexity & Latency Summary

| Operation | Latency | Bottleneck |
|---|---|---|
| Place order (broker side) | < 50ms | Postgres transaction (margin + insert) |
| Place order (exchange round-trip) | 1-5ms | FIX TCP to exchange |
| Cancel order | < 30ms | Exchange cancel + DB update |
| Get portfolio | < 200ms | DB query + Redis price lookup |
| Market data tick to screen | < 100ms | Exchange → Redis Pub/Sub → WS → client |
| Get order book | < 100ms | Postgres indexed query |
| Fund transfer | 1-5 sec | Payment gateway (UPI/NEFT) |

---

## 13. Follow-Up Questions

### Q: Why not use an in-memory DB like Redis for orders instead of Postgres?
Orders involve money. You need ACID transactions for the margin-check-and-order-insert atomic operation. Redis transactions (MULTI/EXEC) don't support the conditional logic needed (check balance, then deduct, then insert). Postgres `FOR UPDATE` row locks give you exactly the isolation you need.

### Q: How do you handle exchange downtime?
Queue orders locally with status `PENDING_EXCHANGE`. When the exchange reconnects, replay queued orders in sequence. Show users "Exchange connectivity issue" on the UI. Never silently drop orders.

### Q: How do you handle partial fills?
The exchange sends multiple ExecutionReport messages. Each partial fill creates a Trade record. The Order status moves to `PARTIALLY_EXECUTED`. Margin is released proportionally. When CumQty == OrderQty, status becomes `EXECUTED`.

### Q: What about after-hours orders (AMO)?
Accept orders after market close, persist with status `AMO_PENDING`. At 9:00 AM next day, a batch job sends all AMO orders to the exchange. Same margin blocking applies at order placement time.

### Q: How do you prevent front-running or insider trading?
Surveillance system (async, not in hot path): Kafka consumers analyze order patterns, flag suspicious activity (large orders before announcements, wash trades). Compliance team reviews flagged orders. This is regulatory requirement (SEBI in India).

---

## 14. Extensions

| Extension | Approach |
|---|---|
| F&O (Futures & Options) | Separate margin engine (SPAN), different order types, expiry handling |
| Algo trading / API access | Rate-limited REST/WebSocket API with API keys, order throttling |
| Mutual funds | Separate service, different settlement (T+1 vs T+2), NAV-based pricing |
| GTT (Good Till Triggered) | Persistent trigger stored in DB, background service monitors price, places order when triggered |
| Basket orders | Accept array of orders, validate margin for entire basket, send as batch |
| Multi-exchange routing | Smart order router picks NSE vs BSE based on best price/liquidity |
