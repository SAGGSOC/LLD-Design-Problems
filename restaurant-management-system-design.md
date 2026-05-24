# Restaurant Management System — Low-Level Design

---

## 1. Requirements

### Functional Requirements

- `createOrder(tableId, serverId, items[]) → orderId` — waiter places an order for a table
- `updateOrderStatus(orderId, status) → confirmation` — kitchen updates order status
- `getTableStatus(tableId) → {status, currentOrder, occupancy}` — host checks table availability
- `reserveTable(partySize, dateTime) → reservationId` — customer books a table
- `generateBill(tableId) → bill` — generates itemized bill with tax
- `getKitchenQueue() → orderedItems[]` — kitchen display shows pending items
- `seatParty(partySize) → tableAssignment` — seat a walk-in, combining adjacent tables if needed
- `releaseTable(tableId) → confirmation` — free table(s) after payment
- `addMenuItem(item) → menuItemId` — manager adds a dish to the menu
- `updateMenuItem(menuItemId, changes) → confirmation` — update price, availability, description
- `removeMenuItem(menuItemId) → confirmation` — soft-delete a menu item
- `getMenu(category?) → menuItems[]` — fetch active menu, optionally filtered by category
- `assignServer(serverId, tableIds[]) → confirmation` — host assigns waiter to section/tables
- `getServerTables(serverId) → tables[]` — waiter views their assigned tables
- `clockIn(staffId, role) / clockOut(staffId)` — staff shift tracking

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| Single restaurant or chain? | Single restaurant, extensible to multi-location |
| How many tables? | 50 tables, capacity 2–8 each |
| Can tables be combined? | Yes — adjacent tables in the same zone |
| Max combined party size? | 20 (up to 4 tables combined) |
| Online ordering? | Out of scope — dine-in only |
| Payment gateway? | Out of scope — bill generation only |
| Real-time kitchen display? | Yes, via WebSocket push |
| Who manages the menu? | Restaurant manager role |
| Staff roles? | Host, Waiter/Server, Kitchen Staff, Manager, Busser |

---

## 2. Staff Roles & Responsibilities

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Staff Roles                                 │
├──────────────┬──────────────────────────────────────────────────────┤
│   Manager    │ Menu CRUD, view reports, override orders, manage     │
│              │ staff assignments, handle complaints                 │
├──────────────┼──────────────────────────────────────────────────────┤
│   Host       │ Greet guests, check reservations, seat parties,     │
│              │ assign servers to sections, manage waitlist          │
├──────────────┼──────────────────────────────────────────────────────┤
│   Waiter     │ Take orders, serve food, request bill, update table  │
│   (Server)   │ status, communicate special requests to kitchen      │
├──────────────┼──────────────────────────────────────────────────────┤
│   Kitchen    │ View kitchen queue, update item status (preparing →  │
│   Staff      │ ready), manage prep priorities                       │
├──────────────┼──────────────────────────────────────────────────────┤
│   Busser     │ Clear tables, mark tables as clean/available         │
└──────────────┴──────────────────────────────────────────────────────┘
```

---

## 3. Class Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        RestaurantManagementSystem                           │
│─────────────────────────────────────────────────────────────────────────────│
│ - orderService: OrderService                                                │
│ - tableService: TableService                                                │
│ - reservationService: ReservationService                                    │
│ - billService: BillService                                                  │
│ - kitchenDisplayService: KitchenDisplayService                              │
│ - menuService: MenuService                                                  │
│ - staffService: StaffService                                                │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┐       ┌──────────────────────────────┐
│       TableService           │       │      OrderService            │
│──────────────────────────────│       │──────────────────────────────│
│ - tables: Map<String, Table> │       │ - orders: Map<String, Order> │
│ - tableGroups: Map<String,   │       │ - tableService: TableService │
│     TableGroup>              │       │ - kitchenService: Kitchen..  │
│──────────────────────────────│       │ - menuService: MenuService   │
│ + getTable(id): Table        │       │──────────────────────────────│
│ + getAvailableTables(): List │       │ + createOrder(tableId,       │
│ + seatParty(partySize):      │       │     serverId, items[]): Order│
│     TableAssignment          │       │ + updateItemStatus(orderId,  │
│ + combineTables(tableIds[]):  │       │     itemSeq, status): void   │
│     TableGroup               │       │ + addItems(orderId,          │
│ + splitTableGroup(groupId):   │       │     items[]): void           │
│     void                     │       │ + cancelItem(orderId,        │
│ + releaseTable(id): void     │       │     itemSeq): void           │
│ + findBestFit(partySize):    │       │ + getOrder(id): Order        │
│     Table or TableGroup      │       └──────────────────────────────┘
└──────────────────────────────┘

┌──────────────────────────────┐       ┌──────────────────────────────┐
│       MenuService            │       │      StaffService            │
│──────────────────────────────│       │──────────────────────────────│
│ - menuItems: Map<String,     │       │ - staff: Map<String, Staff>  │
│     MenuItem>                │       │ - assignments: Map<String,   │
│──────────────────────────────│       │     List<String>>            │
│ + addItem(item): MenuItem    │       │   (serverId → tableIds)      │
│ + updateItem(id, changes):   │       │──────────────────────────────│
│     MenuItem                 │       │ + clockIn(staffId, role):    │
│ + removeItem(id): void       │       │     ShiftRecord              │
│ + getMenu(category?):        │       │ + clockOut(staffId):         │
│     List<MenuItem>           │       │     ShiftRecord              │
│ + getItem(id): MenuItem      │       │ + assignTables(serverId,     │
│ + isAvailable(id): boolean   │       │     tableIds[]): void        │
└──────────────────────────────┘       │ + getServerTables(serverId): │
                                       │     List<String>             │
┌──────────────────────────────┐       │ + getActiveStaff(role?):     │
│       BillService            │       │     List<Staff>              │
│──────────────────────────────│       └──────────────────────────────┘
│ - bills: Map<String, Bill>   │
│ - taxRate: double            │       ┌──────────────────────────────┐
│──────────────────────────────│       │   ReservationService         │
│ + generateBill(tableId):     │       │──────────────────────────────│
│     Bill                     │       │ - reservations: Map<String,  │
│ + getBill(id): Bill          │       │     Reservation>             │
│ + addTip(billId, amount):    │       │ - tableService: TableService │
│     Bill                     │       │──────────────────────────────│
└──────────────────────────────┘       │ + reserve(partySize,         │
                                       │     dateTime): Reservation   │
┌──────────────────────────────┐       │ + cancel(resId): void        │
│  KitchenDisplayService       │       │ + checkIn(resId):            │
│──────────────────────────────│       │     TableAssignment          │
│ - queue: PriorityQueue       │       └──────────────────────────────┘
│ - observers: List<Observer>  │
│──────────────────────────────│
│ + addToQueue(orderItem): void│
│ + getQueue(): List<KitchenItem>│
│ + markPreparing(itemId): void│
│ + markReady(itemId): void    │
│ + subscribe(observer): void  │
└──────────────────────────────┘
```

### Entity Classes

```
┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│       Table           │    │      TableGroup       │    │    TableAssignment    │
│──────────────────────│    │──────────────────────│    │──────────────────────│
│ - tableId: String     │    │ - groupId: String     │    │ - tableIds: List     │
│ - capacity: int       │    │ - tables: List<Table> │    │ - groupId: String    │
│ - status: TableStatus │    │ - combinedCapacity    │    │ - totalCapacity: int │
│ - zone: String        │    │ - primaryTableId      │    │ - zone: String       │
│ - adjacentTableIds:   │    │ - status: TableStatus │    └──────────────────────┘
│     List<String>      │    │ - zone: String        │
│ - currentOrderId      │    └──────────────────────┘
│ - currentGroupId      │
│ - assignedServerId    │
└──────────────────────┘

┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│       Order           │    │      OrderItem        │    │      MenuItem         │
│──────────────────────│    │──────────────────────│    │──────────────────────│
│ - orderId: String     │    │ - itemSeq: int        │    │ - menuItemId: String  │
│ - tableId: String     │    │ - menuItemId: String  │    │ - name: String        │
│ - serverId: String    │    │ - quantity: int        │    │ - description: String │
│ - items: List<Item>   │    │ - priceAtOrder: double│    │ - category: Category  │
│ - status: OrderStatus │    │ - specialInstructions │    │ - price: double       │
│ - createdAt: Instant  │    │ - status: ItemStatus  │    │ - prepTimeMin: int    │
│ - totalAmount: double │    └──────────────────────┘    │ - available: boolean  │
└──────────────────────┘                                 │ - imageUrl: String    │
                                                         │ - allergens: List     │
┌──────────────────────┐    ┌──────────────────────┐    │ - isDeleted: boolean  │
│     Reservation       │    │        Bill            │    └──────────────────────┘
│──────────────────────│    │──────────────────────│
│ - reservationId       │    │ - billId: String      │    ┌──────────────────────┐
│ - customerName        │    │ - orderId: String     │    │       Staff           │
│ - partySize: int      │    │ - lineItems: List     │    │──────────────────────│
│ - dateTime: Instant   │    │ - subtotal: double    │    │ - staffId: String     │
│ - tableIds: List      │    │ - tax: double         │    │ - name: String        │
│ - status: ResStatus   │    │ - tip: double         │    │ - role: StaffRole     │
│ - needsCombined: bool │    │ - total: double       │    │ - activeShift:        │
└──────────────────────┘    │ - generatedAt: Instant│    │     ShiftRecord       │
                            └──────────────────────┘    │ - assignedTableIds:   │
                                                         │     List<String>      │
┌──────────────────────┐                                 └──────────────────────┘
│     ShiftRecord       │
│──────────────────────│
│ - shiftId: String     │
│ - staffId: String     │
│ - role: StaffRole     │
│ - clockIn: Instant    │
│ - clockOut: Instant   │
└──────────────────────┘
```

---

## 4. Enums & State Machines

```java
public enum TableStatus    { AVAILABLE, OCCUPIED, RESERVED, COMBINED, CLEANING }
public enum OrderStatus    { RECEIVED, PREPARING, READY, SERVED, CANCELLED }
public enum ItemStatus     { QUEUED, PREPARING, READY, SERVED, CANCELLED }
public enum StaffRole      { MANAGER, HOST, WAITER, KITCHEN_STAFF, BUSSER }
public enum MenuCategory   { APPETIZER, MAIN_COURSE, DESSERT, BEVERAGE, SIDE, SPECIAL }
public enum ReservationStatus { CONFIRMED, CHECKED_IN, CANCELLED, NO_SHOW }
```

### Order Item Status State Machine

```
┌────────┐     ┌────────────┐     ┌───────┐     ┌────────┐
│ QUEUED │────▶│ PREPARING  │────▶│ READY │────▶│ SERVED │
└────────┘     └────────────┘     └───────┘     └────────┘
     │               │                │
     ▼               ▼                ▼
┌───────────┐  ┌───────────┐   ┌───────────┐
│ CANCELLED │  │ CANCELLED │   │ CANCELLED │
└───────────┘  └───────────┘   └───────────┘
```

### Table Status State Machine

```
                    ┌───────────┐
              ┌────▶│ RESERVED  │────┐
              │     └───────────┘    │ (party arrives)
              │                      ▼
┌───────────┐ │     ┌───────────┐   ┌──────────┐
│ AVAILABLE │─┼────▶│ OCCUPIED  │──▶│ CLEANING │──▶ AVAILABLE
└───────────┘ │     └───────────┘   └──────────┘
              │                      ▲
              │     ┌───────────┐    │
              └────▶│ COMBINED  │────┘
                    └───────────┘
```

---

## 5. DynamoDB Table Design

### 5.1 Tables Table (On-Demand)

```
Table Name: RestaurantTables

PK: tableId (String)        e.g. "T-01"

Attributes:
  capacity        (N)       e.g. 4
  zone            (S)       e.g. "patio"
  status          (S)       e.g. "AVAILABLE"
  adjacentTableIds (SS)     e.g. {"T-02", "T-03"}
  currentOrderId  (S)       e.g. "ORD-abc123"
  currentGroupId  (S)       e.g. "GRP-xyz789"
  assignedServerId (S)      e.g. "STF-waiter01"

GSI: StatusZoneIndex
  PK: status (S)
  SK: zone (S)
  → Query all AVAILABLE tables in a zone for seating logic
```

### 5.2 Orders Table (On-Demand)

```
Table Name: Orders

PK: orderId (String)        e.g. "ORD-20260315-001"

Attributes:
  tableId         (S)       e.g. "T-05"
  serverId        (S)       e.g. "STF-waiter01"
  items           (L)       List of OrderItem maps
  status          (S)       e.g. "PREPARING"
  createdAt       (S)       ISO-8601 timestamp
  totalAmount     (N)       e.g. 85.50

GSI: TableOrderIndex
  PK: tableId (S)
  SK: createdAt (S)
  → Get all orders for a table, sorted by time (current + history)

GSI: ServerOrderIndex
  PK: serverId (S)
  SK: createdAt (S)
  → Waiter views their active orders across tables

GSI: StatusIndex
  PK: status (S)
  SK: createdAt (S)
  → Kitchen queries all RECEIVED/PREPARING orders
```

### 5.3 Menu Table (On-Demand)

```
Table Name: MenuItems

PK: menuItemId (String)     e.g. "MENU-pasta-001"

Attributes:
  name            (S)       e.g. "Truffle Pasta"
  description     (S)       e.g. "Fresh tagliatelle with black truffle"
  category        (S)       e.g. "MAIN_COURSE"
  price           (N)       e.g. 24.99
  prepTimeMin     (N)       e.g. 15
  available       (BOOL)    e.g. true
  allergens       (SS)      e.g. {"gluten", "dairy"}
  imageUrl        (S)       e.g. "https://..."
  isDeleted       (BOOL)    e.g. false   (soft delete)
  updatedAt       (S)       ISO-8601

GSI: CategoryAvailabilityIndex
  PK: category (S)
  SK: available (S)         "true" / "false" as string for SK
  → Fetch all available appetizers, mains, etc.
```

### 5.4 Reservations Table (On-Demand)

```
Table Name: Reservations

PK: reservationId (String)  e.g. "RES-20260315-042"

Attributes:
  customerName    (S)       e.g. "John"
  customerPhone   (S)       e.g. "+1-555-0100"
  partySize       (N)       e.g. 6
  dateTime        (S)       ISO-8601  e.g. "2026-03-15T19:00:00Z"
  status          (S)       e.g. "CONFIRMED"
  tableIds        (SS)      e.g. {"T-05", "T-06"}
  needsCombined   (BOOL)    e.g. true
  createdAt       (S)       ISO-8601

GSI: DateTimeIndex
  PK: date (S)              e.g. "2026-03-15"  (date portion only)
  SK: dateTime (S)          full ISO-8601
  → Host checks all reservations for today, sorted by time

GSI: StatusDateIndex
  PK: status (S)
  SK: dateTime (S)
  → Query all CONFIRMED reservations for upcoming check-ins
```

### 5.5 Staff Table (On-Demand)

```
Table Name: Staff

PK: staffId (String)        e.g. "STF-waiter01"

Attributes:
  name            (S)       e.g. "Alice"
  role            (S)       e.g. "WAITER"
  assignedTableIds (SS)     e.g. {"T-05", "T-06", "T-07"}
  activeShiftId   (S)       e.g. "SHF-20260315-001"
  isOnDuty        (BOOL)    e.g. true

GSI: RoleDutyIndex
  PK: role (S)
  SK: isOnDuty (S)          "true" / "false"
  → Get all on-duty waiters, all on-duty kitchen staff, etc.
```

### 5.6 Shifts Table (On-Demand)

```
Table Name: Shifts

PK: staffId (String)        e.g. "STF-waiter01"
SK: clockIn (String)        ISO-8601  e.g. "2026-03-15T09:00:00Z"

Attributes:
  shiftId         (S)       e.g. "SHF-20260315-001"
  role            (S)       e.g. "WAITER"
  clockOut        (S)       ISO-8601 (null until shift ends)

→ Query all shifts for a staff member, sorted by clockIn
→ Get current shift: query staffId with SK descending, limit 1
```

### 5.7 Bills Table (On-Demand)

```
Table Name: Bills

PK: billId (String)         e.g. "BILL-20260315-005"

Attributes:
  orderId         (S)       e.g. "ORD-20260315-001"
  tableId         (S)       e.g. "T-05"
  serverId        (S)       e.g. "STF-waiter01"
  lineItems       (L)       List of {name, qty, unitPrice, total}
  subtotal        (N)       e.g. 85.50
  taxRate         (N)       e.g. 0.08
  tax             (N)       e.g. 6.84
  tip             (N)       e.g. 15.00
  total           (N)       e.g. 107.34
  generatedAt     (S)       ISO-8601

GSI: TableBillIndex
  PK: tableId (S)
  SK: generatedAt (S)
  → Lookup bill history for a table
```

### 5.8 KitchenQueue Table (On-Demand)

```
Table Name: KitchenQueue

PK: orderId (String)        e.g. "ORD-20260315-001"
SK: itemSeq (Number)        e.g. 1

Attributes:
  menuItemId      (S)       e.g. "MENU-pasta-001"
  itemName        (S)       e.g. "Truffle Pasta"
  quantity        (N)       e.g. 2
  specialInstructions (S)   e.g. "no onions"
  status          (S)       e.g. "QUEUED"
  priority        (N)       e.g. 1 (lower = higher priority)
  tableId         (S)       e.g. "T-05"
  queuedAt        (S)       ISO-8601

GSI: StatusPriorityIndex
  PK: status (S)
  SK: queuedAt (S)
  → Kitchen display: all QUEUED items sorted by time (FIFO)
  → All PREPARING items to track what's being worked on
```

### DynamoDB Table Summary

```
┌─────────────────────┬──────────────┬──────────────┬──────────────────────────────┐
│ Table               │ PK           │ SK           │ GSIs                         │
├─────────────────────┼──────────────┼──────────────┼──────────────────────────────┤
│ RestaurantTables    │ tableId      │ —            │ StatusZoneIndex              │
│ Orders              │ orderId      │ —            │ TableOrderIndex,             │
│                     │              │              │ ServerOrderIndex, StatusIndex │
│ MenuItems           │ menuItemId   │ —            │ CategoryAvailabilityIndex    │
│ Reservations        │ reservationId│ —            │ DateTimeIndex,               │
│                     │              │              │ StatusDateIndex              │
│ Staff               │ staffId      │ —            │ RoleDutyIndex                │
│ Shifts              │ staffId      │ clockIn      │ —                            │
│ Bills               │ billId       │ —            │ TableBillIndex               │
│ KitchenQueue        │ orderId      │ itemSeq      │ StatusPriorityIndex          │
└─────────────────────┴──────────────┴──────────────┴──────────────────────────────┘

All tables use On-Demand capacity mode (pay-per-request) — suitable for
restaurant workloads with spiky traffic (lunch/dinner rush vs. idle hours).
```

---

## 6. Flow Diagrams

### Flow 1: Guest Arrival & Seating (Host + Waiter)

```
Guest arrives
     │
     ▼
┌──────────────────┐    Yes    ┌───────────────────┐
│ Has reservation? │──────────▶│ Host looks up     │
└────────┬─────────┘           │ reservation by    │
         │ No                  │ DateTimeIndex     │
         ▼                     └────────┬──────────┘
┌──────────────────┐                    │
│ Host checks      │                    ▼
│ available tables  │           ┌───────────────────┐
│ (StatusZoneIndex) │           │ Mark reservation  │
└────────┬─────────┘           │ CHECKED_IN        │
         │                     └────────┬──────────┘
         ▼                              │
┌──────────────────────────┐            │
│ seatParty(partySize)     │◀───────────┘
│                          │
│ 1. Try single table fit  │
│    (smallest capacity ≥  │
│     partySize)           │
│                          │
│ 2. If none → BFS to find │
│    adjacent tables in    │
│    same zone, combine    │
│    them into TableGroup  │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Tables marked OCCUPIED   │
│ or COMBINED in DDB       │
│                          │
│ Host assigns a waiter:   │
│ assignServer(serverId,   │
│   tableIds)              │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Waiter greets table,     │
│ provides menu            │
└──────────────────────────┘
```

### Flow 2: Order Placement & Kitchen (Waiter + Kitchen Staff)

```
Waiter takes order at table
     │
     ▼
┌──────────────────────────┐
│ createOrder(tableId,     │
│   serverId, items[])     │
│                          │
│ For each item:           │
│  - Validate menuItemId   │
│    exists & available    │
│  - Snapshot priceAtOrder │
│    from MenuItems table  │
│  - Set ItemStatus=QUEUED │
└────────┬─────────────────┘
         │
         ├──────────────────────────────┐
         ▼                              ▼
┌──────────────────────┐    ┌──────────────────────────┐
│ Write to Orders      │    │ Write each item to       │
│ table in DDB         │    │ KitchenQueue table       │
│ status = RECEIVED    │    │ status = QUEUED          │
└──────────────────────┘    │ priority by prepTime     │
                            └────────┬─────────────────┘
                                     │
                                     ▼
                            ┌──────────────────────────┐
                            │ WebSocket push to        │
                            │ Kitchen Display          │
                            │                          │
                            │ Kitchen staff sees new   │
                            │ items in FIFO order      │
                            │ (StatusPriorityIndex)    │
                            └────────┬─────────────────┘
                                     │
                                     ▼
                            ┌──────────────────────────┐
                            │ Kitchen staff picks item  │
                            │ → markPreparing(itemId)  │
                            │ → status = PREPARING     │
                            │                          │
                            │ When done cooking:       │
                            │ → markReady(itemId)      │
                            │ → status = READY         │
                            └────────┬─────────────────┘
                                     │
                                     ▼
                            ┌──────────────────────────┐
                            │ WebSocket notifies       │
                            │ waiter: "Table T-05      │
                            │ Truffle Pasta READY"     │
                            │                          │
                            │ Waiter picks up food,    │
                            │ serves to table          │
                            │ → status = SERVED        │
                            └──────────────────────────┘

When ALL items in order are SERVED → Order status = SERVED
```

### Flow 3: Bill Generation & Table Release (Waiter + Busser)

```
Guest asks for the check
     │
     ▼
┌──────────────────────────┐
│ Waiter calls              │
│ generateBill(tableId)    │
│                          │
│ 1. Lookup order by       │
│    TableOrderIndex       │
│ 2. For each OrderItem:   │
│    build line item with  │
│    name, qty, unitPrice  │
│ 3. Calculate subtotal    │
│ 4. Apply tax rate        │
│ 5. Write Bill to DDB     │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Waiter presents bill     │
│ Guest pays (out of scope)│
│                          │
│ Waiter optionally:       │
│ addTip(billId, amount)   │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ releaseTable(tableId)    │
│                          │
│ If table was COMBINED:   │
│  → releaseTableGroup()   │
│  → all tables in group   │
│    set to CLEANING       │
│  → split group, clear    │
│    currentGroupId        │
│                          │
│ If single table:         │
│  → set CLEANING          │
│  → clear currentOrderId  │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Busser clears table      │
│ markClean(tableId)       │
│ → status = AVAILABLE     │
│                          │
│ Table ready for next     │
│ party                    │
└──────────────────────────┘
```

### Flow 4: Menu Management (Manager)

```
Manager wants to update menu
     │
     ├─── Add new dish ──────────────────────────┐
     │                                            ▼
     │                               ┌──────────────────────────┐
     │                               │ addMenuItem(item)        │
     │                               │                          │
     │                               │ Validate:                │
     │                               │  - name not duplicate    │
     │                               │  - price > 0             │
     │                               │  - category valid        │
     │                               │                          │
     │                               │ Write to MenuItems DDB   │
     │                               │ available = true         │
     │                               │ isDeleted = false        │
     │                               └──────────────────────────┘
     │
     ├─── Update existing dish ──────────────────┐
     │                                            ▼
     │                               ┌──────────────────────────┐
     │                               │ updateMenuItem(id,       │
     │                               │   {price, available,     │
     │                               │    description, ...})    │
     │                               │                          │
     │                               │ Common use cases:        │
     │                               │  - 86 an item (set       │
     │                               │    available = false)    │
     │                               │  - Seasonal price change │
     │                               │  - Update allergens      │
     │                               │                          │
     │                               │ Note: existing orders    │
     │                               │ keep priceAtOrder — not  │
     │                               │ affected by price change │
     │                               └──────────────────────────┘
     │
     └─── Remove dish ──────────────────────────┐
                                                 ▼
                                    ┌──────────────────────────┐
                                    │ removeMenuItem(id)       │
                                    │                          │
                                    │ Soft delete:             │
                                    │  isDeleted = true        │
                                    │  available = false       │
                                    │                          │
                                    │ Item stays in DDB for    │
                                    │ historical order lookups │
                                    │ but won't appear in      │
                                    │ getMenu() results        │
                                    └──────────────────────────┘
```

### Flow 5: Staff Shift & Table Assignment (Host + Waiter)

```
Start of shift
     │
     ▼
┌──────────────────────────┐
│ Staff clocks in          │
│ clockIn(staffId, role)   │
│                          │
│ 1. Write ShiftRecord to  │
│    Shifts table          │
│    PK=staffId, SK=now    │
│ 2. Update Staff table:   │
│    isOnDuty = true       │
│    activeShiftId = new   │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Host assigns sections    │
│ assignTables(serverId,   │
│   [T-05, T-06, T-07])   │
│                          │
│ Update Staff record:     │
│ assignedTableIds = {...} │
│                          │
│ Update each Table:       │
│ assignedServerId = ...   │
└────────┬─────────────────┘
         │
         ▼
  (waiter works their shift — takes orders, serves, etc.)
         │
         ▼
┌──────────────────────────┐
│ End of shift             │
│ clockOut(staffId)        │
│                          │
│ 1. Update Shifts record: │
│    clockOut = now        │
│ 2. Update Staff table:   │
│    isOnDuty = false      │
│    clear assignedTableIds│
│ 3. Host reassigns tables │
│    to another waiter     │
└──────────────────────────┘
```

### Flow 6: Table Combination for Large Party

```
Party of 14 arrives (no single table fits max 8)
     │
     ▼
┌──────────────────────────────────────────────────┐
│ seatParty(14)                                     │
│                                                   │
│ Step 1: Single table search — FAILS (max cap 8)  │
│                                                   │
│ Step 2: BFS from each available table             │
│                                                   │
│   Zone "main-hall":                               │
│   ┌─────┐   ┌─────┐   ┌─────┐   ┌─────┐        │
│   │T-10 │───│T-11 │───│T-12 │───│T-13 │        │
│   │cap:4│   │cap:4│   │cap:6│   │cap:4│        │
│   │AVAIL│   │AVAIL│   │AVAIL│   │OCCUP│        │
│   └─────┘   └─────┘   └─────┘   └─────┘        │
│                                                   │
│   BFS from T-10 finds cluster: {T-10, T-11, T-12}│
│   Combined capacity: 4 + 4 + 6 = 14  ✓           │
│                                                   │
│ Step 3: findMinimalSubset — all 3 needed          │
│ Step 4: isConnectedSubset — verified adjacent     │
└────────────────┬─────────────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────────┐
│ Create TableGroup:                                │
│   groupId = "GRP-1710504000"                     │
│   tables = [T-10, T-11, T-12]                    │
│   primaryTableId = "T-10"                        │
│   combinedCapacity = 14                          │
│                                                   │
│ Update DDB:                                       │
│   T-10: status=COMBINED, groupId=GRP-...         │
│   T-11: status=COMBINED, groupId=GRP-...         │
│   T-12: status=COMBINED, groupId=GRP-...         │
│                                                   │
│ Orders placed against primaryTableId "T-10"      │
│ Bill generated against "T-10"                    │
└────────────────┬─────────────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────────┐
│ After payment: releaseTable("T-10")              │
│ → detects group → releaseTableGroup("GRP-...")   │
│ → T-10, T-11, T-12 all set to CLEANING          │
│ → group removed from activeGroups                │
│ → busser cleans → markClean each → AVAILABLE     │
└──────────────────────────────────────────────────┘
```

---

## 7. Core Java Implementation

### 7.1 Table, TableGroup & TableAssignment

```java
public class Table {
    private final String tableId;
    private final int capacity;
    private final String zone;
    private final List<String> adjacentTableIds;
    private TableStatus status;
    private String currentOrderId;
    private String currentGroupId;
    private String assignedServerId;

    public Table(String tableId, int capacity, String zone, List<String> adjacentTableIds) {
        this.tableId = tableId;
        this.capacity = capacity;
        this.zone = zone;
        this.adjacentTableIds = adjacentTableIds;
        this.status = TableStatus.AVAILABLE;
    }

    public boolean isAvailable()    { return status == TableStatus.AVAILABLE; }
    public boolean isPartOfGroup()  { return currentGroupId != null; }

    // getters, setters omitted for brevity
}

public class TableGroup {
    private final String groupId;
    private final List<Table> tables;
    private final String primaryTableId;
    private final int combinedCapacity;
    private final String zone;

    public TableGroup(String groupId, List<Table> tables) {
        this.groupId = groupId;
        this.tables = tables;
        this.primaryTableId = tables.get(0).getTableId();
        this.combinedCapacity = tables.stream().mapToInt(Table::getCapacity).sum();
        this.zone = tables.get(0).getZone();
    }
}

public class TableAssignment {
    private final List<String> tableIds;
    private final String groupId;   // null if single table
    private final int totalCapacity;
    private final String zone;

    public TableAssignment(Table single) {
        this.tableIds = List.of(single.getTableId());
        this.groupId = null;
        this.totalCapacity = single.getCapacity();
        this.zone = single.getZone();
    }

    public TableAssignment(TableGroup group) {
        this.tableIds = group.getTables().stream()
            .map(Table::getTableId).collect(Collectors.toList());
        this.groupId = group.getGroupId();
        this.totalCapacity = group.getCombinedCapacity();
        this.zone = group.getZone();
    }

    public boolean isCombined() { return groupId != null; }
}
```

### 7.2 TableService — Seating & Table Combination (BFS)

```java
public class TableService {
    private final Map<String, Table> tables;
    private final Map<String, TableGroup> activeGroups;

    public TableService(List<Table> allTables) {
        this.tables = new LinkedHashMap<>();
        for (Table t : allTables) tables.put(t.getTableId(), t);
        this.activeGroups = new ConcurrentHashMap<>();
    }

    // ─── Seat a party: single table or combined ───

    public synchronized TableAssignment seatParty(int partySize) {
        // Strategy 1: single table (smallest that fits)
        Optional<Table> singleFit = tables.values().stream()
            .filter(Table::isAvailable)
            .filter(t -> t.getCapacity() >= partySize)
            .min(Comparator.comparingInt(Table::getCapacity));

        if (singleFit.isPresent()) {
            Table table = singleFit.get();
            table.setStatus(TableStatus.OCCUPIED);
            return new TableAssignment(table);
        }

        // Strategy 2: combine adjacent tables via BFS
        TableGroup group = findBestCombination(partySize);
        if (group == null) {
            throw new NoTablesAvailableException("No fit for party of " + partySize);
        }

        for (Table t : group.getTables()) {
            t.setStatus(TableStatus.COMBINED);
            t.setCurrentGroupId(group.getGroupId());
        }
        activeGroups.put(group.getGroupId(), group);
        return new TableAssignment(group);
    }

    // ─── BFS to find adjacent table clusters ───

    private TableGroup findBestCombination(int partySize) {
        Set<String> availableIds = tables.values().stream()
            .filter(Table::isAvailable)
            .map(Table::getTableId).collect(Collectors.toSet());

        List<List<Table>> clusters = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (String startId : availableIds) {
            if (visited.contains(startId)) continue;

            List<Table> cluster = new ArrayList<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(startId);
            visited.add(startId);

            while (!queue.isEmpty()) {
                Table current = tables.get(queue.poll());
                cluster.add(current);
                for (String adjId : current.getAdjacentTableIds()) {
                    if (availableIds.contains(adjId) && !visited.contains(adjId)) {
                        visited.add(adjId);
                        queue.add(adjId);
                    }
                }
            }

            if (cluster.stream().mapToInt(Table::getCapacity).sum() >= partySize) {
                clusters.add(cluster);
            }
        }

        // Find smallest subset from each cluster
        TableGroup best = null;
        int bestWaste = Integer.MAX_VALUE;

        for (List<Table> cluster : clusters) {
            List<Table> subset = findMinimalSubset(cluster, partySize);
            if (subset != null) {
                int waste = subset.stream().mapToInt(Table::getCapacity).sum() - partySize;
                if (waste < bestWaste) {
                    bestWaste = waste;
                    best = new TableGroup("GRP-" + System.currentTimeMillis(), subset);
                }
            }
        }
        return best;
    }

    private List<Table> findMinimalSubset(List<Table> cluster, int partySize) {
        List<Table> sorted = cluster.stream()
            .sorted(Comparator.comparingInt(Table::getCapacity).reversed())
            .collect(Collectors.toList());

        List<Table> subset = new ArrayList<>();
        int total = 0;
        for (Table t : sorted) {
            subset.add(t);
            total += t.getCapacity();
            if (total >= partySize && isConnectedSubset(subset)) {
                return subset;
            }
        }
        return null;
    }

    private boolean isConnectedSubset(List<Table> subset) {
        if (subset.size() <= 1) return true;
        Set<String> ids = subset.stream().map(Table::getTableId).collect(Collectors.toSet());
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(subset.get(0).getTableId());
        visited.add(subset.get(0).getTableId());

        while (!queue.isEmpty()) {
            Table cur = tables.get(queue.poll());
            for (String adj : cur.getAdjacentTableIds()) {
                if (ids.contains(adj) && !visited.contains(adj)) {
                    visited.add(adj);
                    queue.add(adj);
                }
            }
        }
        return visited.size() == subset.size();
    }

    // ─── Release ───

    public synchronized void releaseTable(String tableId) {
        Table table = tables.get(tableId);
        if (table.isPartOfGroup()) {
            TableGroup group = activeGroups.remove(table.getCurrentGroupId());
            for (Table t : group.getTables()) {
                t.setStatus(TableStatus.CLEANING);
                t.setCurrentGroupId(null);
                t.setCurrentOrderId(null);
            }
        } else {
            table.setStatus(TableStatus.CLEANING);
            table.setCurrentOrderId(null);
        }
    }

    public synchronized void markClean(String tableId) {
        tables.get(tableId).setStatus(TableStatus.AVAILABLE);
    }
}
```

### 7.3 MenuService

```java
public class MenuService {
    private final Map<String, MenuItem> menuItems = new ConcurrentHashMap<>();

    public MenuItem addItem(String name, String description, MenuCategory category,
                            double price, int prepTimeMin, List<String> allergens) {
        // Validate no duplicate active name in same category
        boolean duplicate = menuItems.values().stream()
            .filter(m -> !m.isDeleted())
            .anyMatch(m -> m.getName().equalsIgnoreCase(name)
                        && m.getCategory() == category);
        if (duplicate) throw new DuplicateMenuItemException(name);
        if (price <= 0) throw new IllegalArgumentException("Price must be positive");

        String id = "MENU-" + category.name().toLowerCase() + "-" + System.currentTimeMillis();
        MenuItem item = new MenuItem(id, name, description, category, price,
                                     prepTimeMin, allergens);
        menuItems.put(id, item);
        return item;
    }

    public MenuItem updateItem(String menuItemId, Map<String, Object> changes) {
        MenuItem item = menuItems.get(menuItemId);
        if (item == null || item.isDeleted()) throw new MenuItemNotFoundException(menuItemId);

        // Apply partial updates
        if (changes.containsKey("price"))       item.setPrice((double) changes.get("price"));
        if (changes.containsKey("available"))   item.setAvailable((boolean) changes.get("available"));
        if (changes.containsKey("description")) item.setDescription((String) changes.get("description"));
        if (changes.containsKey("allergens"))   item.setAllergens((List<String>) changes.get("allergens"));
        if (changes.containsKey("prepTimeMin")) item.setPrepTimeMin((int) changes.get("prepTimeMin"));

        return item;
    }

    public void removeItem(String menuItemId) {
        MenuItem item = menuItems.get(menuItemId);
        if (item == null) throw new MenuItemNotFoundException(menuItemId);
        // Soft delete — keeps item for historical order references
        item.setDeleted(true);
        item.setAvailable(false);
    }

    public List<MenuItem> getMenu(MenuCategory category) {
        return menuItems.values().stream()
            .filter(m -> !m.isDeleted() && m.isAvailable())
            .filter(m -> category == null || m.getCategory() == category)
            .sorted(Comparator.comparing(MenuItem::getCategory)
                              .thenComparing(MenuItem::getName))
            .collect(Collectors.toList());
    }

    public MenuItem getItem(String menuItemId) {
        MenuItem item = menuItems.get(menuItemId);
        if (item == null || item.isDeleted()) throw new MenuItemNotFoundException(menuItemId);
        return item;
    }

    public boolean isAvailable(String menuItemId) {
        MenuItem item = menuItems.get(menuItemId);
        return item != null && !item.isDeleted() && item.isAvailable();
    }
}
```

### 7.4 StaffService

```java
public class StaffService {
    private final Map<String, Staff> staffMap = new ConcurrentHashMap<>();
    private final Map<String, List<ShiftRecord>> shiftHistory = new ConcurrentHashMap<>();

    public ShiftRecord clockIn(String staffId, StaffRole role) {
        Staff staff = staffMap.get(staffId);
        if (staff == null) throw new StaffNotFoundException(staffId);
        if (staff.isOnDuty()) throw new IllegalStateException("Already clocked in");

        ShiftRecord shift = new ShiftRecord(
            "SHF-" + System.currentTimeMillis(), staffId, role, Instant.now());

        staff.setOnDuty(true);
        staff.setActiveShift(shift);
        shiftHistory.computeIfAbsent(staffId, k -> new ArrayList<>()).add(shift);
        return shift;
    }

    public ShiftRecord clockOut(String staffId) {
        Staff staff = staffMap.get(staffId);
        if (staff == null) throw new StaffNotFoundException(staffId);
        if (!staff.isOnDuty()) throw new IllegalStateException("Not clocked in");

        ShiftRecord shift = staff.getActiveShift();
        shift.setClockOut(Instant.now());
        staff.setOnDuty(false);
        staff.setActiveShift(null);
        staff.getAssignedTableIds().clear();
        return shift;
    }

    public void assignTables(String serverId, List<String> tableIds) {
        Staff staff = staffMap.get(serverId);
        if (staff == null) throw new StaffNotFoundException(serverId);
        if (staff.getRole() != StaffRole.WAITER) {
            throw new IllegalArgumentException("Only waiters can be assigned tables");
        }
        staff.setAssignedTableIds(tableIds);
    }

    public List<String> getServerTables(String serverId) {
        Staff staff = staffMap.get(serverId);
        if (staff == null) throw new StaffNotFoundException(serverId);
        return staff.getAssignedTableIds();
    }

    public List<Staff> getActiveStaff(StaffRole role) {
        return staffMap.values().stream()
            .filter(Staff::isOnDuty)
            .filter(s -> role == null || s.getRole() == role)
            .collect(Collectors.toList());
    }
}
```

### 7.5 OrderService

```java
public class OrderService {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final TableService tableService;
    private final MenuService menuService;
    private final KitchenDisplayService kitchenService;

    public OrderService(TableService tableService, MenuService menuService,
                        KitchenDisplayService kitchenService) {
        this.tableService = tableService;
        this.menuService = menuService;
        this.kitchenService = kitchenService;
    }

    public Order createOrder(String tableId, String serverId, List<OrderItemRequest> items) {
        String orderId = "ORD-" + LocalDate.now() + "-" + System.currentTimeMillis();
        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0;

        for (int i = 0; i < items.size(); i++) {
            OrderItemRequest req = items.get(i);

            // Validate menu item exists and is available
            if (!menuService.isAvailable(req.getMenuItemId())) {
                throw new MenuItemUnavailableException(req.getMenuItemId());
            }

            MenuItem menuItem = menuService.getItem(req.getMenuItemId());
            double lineTotal = menuItem.getPrice() * req.getQuantity();

            OrderItem orderItem = new OrderItem(
                i + 1,                          // itemSeq
                req.getMenuItemId(),
                req.getQuantity(),
                menuItem.getPrice(),            // snapshot price at order time
                req.getSpecialInstructions(),
                ItemStatus.QUEUED
            );

            orderItems.add(orderItem);
            total += lineTotal;

            // Push to kitchen queue
            kitchenService.addToQueue(orderId, orderItem, tableId, menuItem);
        }

        Order order = new Order(orderId, tableId, serverId, orderItems,
                                OrderStatus.RECEIVED, Instant.now(), total);
        orders.put(orderId, order);

        // Link order to table
        Table table = tableService.getTable(tableId);
        table.setCurrentOrderId(orderId);

        return order;
    }

    public void updateItemStatus(String orderId, int itemSeq, ItemStatus newStatus) {
        Order order = orders.get(orderId);
        if (order == null) throw new OrderNotFoundException(orderId);

        OrderItem item = order.getItems().stream()
            .filter(i -> i.getItemSeq() == itemSeq)
            .findFirst().orElseThrow(() -> new ItemNotFoundException(itemSeq));

        // Validate state transition
        ItemStatusMachine.validate(item.getStatus(), newStatus);
        item.setStatus(newStatus);

        // Auto-promote order status based on item statuses
        recalculateOrderStatus(order);
    }

    private void recalculateOrderStatus(Order order) {
        List<OrderItem> active = order.getItems().stream()
            .filter(i -> i.getStatus() != ItemStatus.CANCELLED)
            .collect(Collectors.toList());

        if (active.isEmpty()) {
            order.setStatus(OrderStatus.CANCELLED);
        } else if (active.stream().allMatch(i -> i.getStatus() == ItemStatus.SERVED)) {
            order.setStatus(OrderStatus.SERVED);
        } else if (active.stream().anyMatch(i -> i.getStatus() == ItemStatus.PREPARING)) {
            order.setStatus(OrderStatus.PREPARING);
        } else if (active.stream().allMatch(i -> i.getStatus() == ItemStatus.READY
                                              || i.getStatus() == ItemStatus.SERVED)) {
            order.setStatus(OrderStatus.READY);
        }
    }
}
```

### 7.6 BillService

```java
public class BillService {
    private final Map<String, Bill> bills = new ConcurrentHashMap<>();
    private final double taxRate;
    private final OrderService orderService;

    public BillService(OrderService orderService, double taxRate) {
        this.orderService = orderService;
        this.taxRate = taxRate;
    }

    public Bill generateBill(String tableId, String orderId) {
        Order order = orderService.getOrder(orderId);

        List<BillLineItem> lineItems = order.getItems().stream()
            .filter(i -> i.getStatus() != ItemStatus.CANCELLED)
            .map(i -> new BillLineItem(i.getMenuItemId(), i.getQuantity(),
                                       i.getPriceAtOrder(),
                                       i.getQuantity() * i.getPriceAtOrder()))
            .collect(Collectors.toList());

        double subtotal = lineItems.stream().mapToDouble(BillLineItem::getTotal).sum();
        double tax = subtotal * taxRate;

        String billId = "BILL-" + LocalDate.now() + "-" + System.currentTimeMillis();
        Bill bill = new Bill(billId, orderId, tableId, lineItems,
                             subtotal, taxRate, tax, 0.0,
                             subtotal + tax, Instant.now());
        bills.put(billId, bill);
        return bill;
    }

    public Bill addTip(String billId, double tipAmount) {
        Bill bill = bills.get(billId);
        if (bill == null) throw new BillNotFoundException(billId);
        bill.setTip(tipAmount);
        bill.setTotal(bill.getSubtotal() + bill.getTax() + tipAmount);
        return bill;
    }
}
```

### 7.7 KitchenDisplayService

```java
public class KitchenDisplayService {
    private final PriorityQueue<KitchenItem> queue;
    private final List<KitchenObserver> observers = new CopyOnWriteArrayList<>();

    public KitchenDisplayService() {
        // Priority: lower prepTime first (quick items out fast), then FIFO
        this.queue = new PriorityQueue<>(
            Comparator.comparingInt(KitchenItem::getPriority)
                      .thenComparing(KitchenItem::getQueuedAt));
    }

    public void addToQueue(String orderId, OrderItem item,
                           String tableId, MenuItem menuItem) {
        KitchenItem ki = new KitchenItem(
            orderId, item.getItemSeq(), menuItem.getName(),
            item.getQuantity(), item.getSpecialInstructions(),
            tableId, menuItem.getPrepTimeMin(), ItemStatus.QUEUED, Instant.now());

        queue.add(ki);
        notifyObservers("NEW_ITEM", ki);
    }

    public void markPreparing(String orderId, int itemSeq) {
        KitchenItem ki = findItem(orderId, itemSeq);
        ki.setStatus(ItemStatus.PREPARING);
        notifyObservers("PREPARING", ki);
    }

    public void markReady(String orderId, int itemSeq) {
        KitchenItem ki = findItem(orderId, itemSeq);
        ki.setStatus(ItemStatus.READY);
        queue.remove(ki);
        notifyObservers("READY", ki);  // triggers WebSocket push to waiter
    }

    public void subscribe(KitchenObserver observer) {
        observers.add(observer);
    }

    private void notifyObservers(String event, KitchenItem item) {
        for (KitchenObserver obs : observers) obs.onKitchenEvent(event, item);
    }

    private KitchenItem findItem(String orderId, int itemSeq) {
        return queue.stream()
            .filter(ki -> ki.getOrderId().equals(orderId) && ki.getItemSeq() == itemSeq)
            .findFirst().orElseThrow(() -> new ItemNotFoundException(itemSeq));
    }
}

// Observer interface for WebSocket push
public interface KitchenObserver {
    void onKitchenEvent(String event, KitchenItem item);
}
```

---

## 8. DDB Write Triggers — Who Writes What & When

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                        DDB WRITE MAP BY ACTION                                       │
├──────────────────────┬───────────────┬───────────────────┬───────────────────────────┤
│ Action               │ Triggered By  │ Tables Written    │ Write Type               │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ clockIn(staffId)     │ Any Staff     │ Shifts            │ PutItem (new shift row)  │
│                      │               │ Staff             │ UpdateItem (isOnDuty,    │
│                      │               │                   │   activeShiftId)         │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ clockOut(staffId)    │ Any Staff     │ Shifts            │ UpdateItem (clockOut)    │
│                      │               │ Staff             │ UpdateItem (isOnDuty=    │
│                      │               │                   │   false, clear tables)   │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ assignTables(server, │ Host          │ Staff             │ UpdateItem               │
│   tableIds)          │               │                   │   (assignedTableIds)     │
│                      │               │ RestaurantTables  │ UpdateItem each table    │
│                      │               │                   │   (assignedServerId)     │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ addMenuItem(item)    │ Manager       │ MenuItems         │ PutItem (new menu row)   │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ updateMenuItem(id,   │ Manager       │ MenuItems         │ UpdateItem (price,       │
│   changes)           │               │                   │   available, desc, etc.) │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ removeMenuItem(id)   │ Manager       │ MenuItems         │ UpdateItem (isDeleted=   │
│                      │               │                   │   true, available=false) │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ reserve(partySize,   │ Customer /    │ Reservations      │ PutItem (new reservation)│
│   dateTime)          │ Host          │                   │                          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ seatParty(partySize) │ Host          │ RestaurantTables  │ UpdateItem per table:    │
│                      │               │                   │   status → OCCUPIED or   │
│                      │               │                   │   COMBINED, set groupId  │
│  (if reservation)    │               │ Reservations      │ UpdateItem (status →     │
│                      │               │                   │   CHECKED_IN, tableIds)  │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ createOrder(tableId, │ Waiter        │ Orders            │ PutItem (new order)      │
│   serverId, items)   │               │ KitchenQueue      │ PutItem per item         │
│                      │               │ RestaurantTables  │ UpdateItem               │
│                      │               │                   │   (currentOrderId)       │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ markPreparing(order, │ Kitchen Staff │ KitchenQueue      │ UpdateItem (status →     │
│   itemSeq)           │               │                   │   PREPARING)             │
│                      │               │ Orders            │ UpdateItem (item status, │
│                      │               │                   │   order status recalc)   │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ markReady(order,     │ Kitchen Staff │ KitchenQueue      │ UpdateItem (status →     │
│   itemSeq)           │               │                   │   READY)                 │
│                      │               │ Orders            │ UpdateItem (item status) │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ serve item (waiter   │ Waiter        │ KitchenQueue      │ DeleteItem (remove from  │
│   marks SERVED)      │               │                   │   active queue)          │
│                      │               │ Orders            │ UpdateItem (item →       │
│                      │               │                   │   SERVED, recalc order)  │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ generateBill(tableId)│ Waiter        │ Bills             │ PutItem (new bill)       │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ addTip(billId, amt)  │ Waiter        │ Bills             │ UpdateItem (tip, total)  │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ releaseTable(tableId)│ Waiter / Host │ RestaurantTables  │ UpdateItem per table:    │
│                      │               │                   │   status → CLEANING,     │
│                      │               │                   │   clear orderId, groupId │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ markClean(tableId)   │ Busser        │ RestaurantTables  │ UpdateItem (status →     │
│                      │               │                   │   AVAILABLE)             │
└──────────────────────┴───────────────┴───────────────────┴───────────────────────────┘
```

### Write Flow Per Table — Visual

```
RestaurantTables ◀── seatParty (OCCUPIED/COMBINED)
                 ◀── assignTables (assignedServerId)
                 ◀── createOrder (currentOrderId)
                 ◀── releaseTable (CLEANING, clear refs)
                 ◀── markClean (AVAILABLE)

Orders           ◀── createOrder (PutItem)
                 ◀── markPreparing / markReady / serve (UpdateItem status)

KitchenQueue     ◀── createOrder (PutItem per item)
                 ◀── markPreparing / markReady (UpdateItem)
                 ◀── serve (DeleteItem — done, off the queue)

MenuItems        ◀── addMenuItem (PutItem)
                 ◀── updateMenuItem (UpdateItem)
                 ◀── removeMenuItem (UpdateItem soft-delete)

Reservations     ◀── reserve (PutItem)
                 ◀── seatParty/checkIn (UpdateItem → CHECKED_IN)
                 ◀── cancel (UpdateItem → CANCELLED)

Staff            ◀── clockIn (UpdateItem isOnDuty=true)
                 ◀── clockOut (UpdateItem isOnDuty=false)
                 ◀── assignTables (UpdateItem assignedTableIds)

Shifts           ◀── clockIn (PutItem — new shift record)
                 ◀── clockOut (UpdateItem — set clockOut timestamp)

Bills            ◀── generateBill (PutItem)
                 ◀── addTip (UpdateItem)
```

### Conditional Writes & Consistency Notes

```
1. seatParty uses a DDB conditional write:
   ConditionExpression: "status = :available"
   → Prevents two hosts from seating the same table simultaneously.
   → If condition fails, retry with next best table.

2. createOrder validates menu availability with a consistent read
   on MenuItems BEFORE writing the order — ensures no stale "available"
   flag from eventual consistency.

3. markPreparing / markReady use conditional writes:
   ConditionExpression: "status = :expectedPrevious"
   → QUEUED → PREPARING → READY (enforces state machine at DB level)

4. releaseTable on a COMBINED group uses a DDB TransactWriteItems
   to atomically update all tables in the group:
   → All tables set to CLEANING in one transaction
   → Prevents partial release if one update fails

5. generateBill reads order with ConsistentRead=true
   → Ensures all item statuses are up-to-date before billing
```

---

## 9. End-to-End Scenario: Dinner Service

```
6:00 PM  Manager updates menu — 86s the salmon (available=false)
         Adds tonight's special: "Lobster Risotto" to SPECIAL category

6:30 PM  Waiters clock in: Alice (T-01..T-10), Bob (T-11..T-20)
         Kitchen staff clock in: Chef Marco, Sous Chef Priya
         Host Sarah clocks in

7:00 PM  Party of 6 walks in
         → Sarah calls seatParty(6) → T-08 (cap 6) assigned
         → Sarah assigns T-08 to Alice
         → Alice greets, hands menu (getMenu returns active items only)

7:05 PM  Alice takes order: 2x Truffle Pasta, 1x Lobster Risotto, 3x Caesar Salad
         → createOrder("T-08", "Alice", items)
         → Kitchen display shows 6 items in queue

7:08 PM  Chef Marco picks up Lobster Risotto → PREPARING
         Sous Chef Priya picks up pastas → PREPARING

7:15 PM  Party of 14 arrives (reservation)
         → Sarah calls checkIn(resId) → seatParty(14)
         → BFS finds T-11 + T-12 + T-13 (4+6+4=14) → COMBINED
         → Assigned to Bob

7:20 PM  Salads READY → WebSocket notifies Alice → serves to T-08
         Pastas READY → Alice serves

7:25 PM  Risotto READY → Alice serves → all items SERVED → order SERVED

7:40 PM  T-08 asks for check → generateBill("T-08")
         → Bill: subtotal $142.50, tax $11.40, total $153.90
         → Guest pays, leaves tip → addTip(billId, 25.00) → $178.90

7:45 PM  releaseTable("T-08") → CLEANING
         Busser clears → markClean("T-08") → AVAILABLE

9:00 PM  Party of 14 finishes → releaseTable("T-11")
         → detects group → splits T-11, T-12, T-13 back → CLEANING
         → Busser cleans all three → AVAILABLE
```
