# Interview Progression: Problem → API Design → LLD

## The Pattern

Interviews at Amazon/FAANG often follow a 3-stage escalation:

```
Small Problem → API Design → LLD with Design Patterns
     (20 min)      (15 min)         (25 min)
```

Each stage tests a different skill. The key is recognizing the transition and shifting gears.

---

## Stage 1: The Small Problem

**What they're testing:** Can you write correct, clean code under pressure?

### What to do:
1. Clarify inputs/outputs (types, ranges, edge cases)
2. State your approach in 1-2 sentences before coding
3. Write clean code with meaningful names
4. Handle edge cases (empty input, nulls, single element)
5. State time/space complexity

### Common traps:
- Jumping into code without clarifying
- Not handling empty/null inputs
- Off-by-one errors
- Not testing with a small example

### Template:
```java
public ReturnType solve(InputType input) {
    // 1. Validate
    if (input == null || input.isEmpty()) return defaultValue;
    
    // 2. Core logic
    // ...
    
    // 3. Return
    return result;
}
```

---

## Stage 2: API Design Extension

**Interviewer says something like:**
> "Now imagine this is an API that other teams will call. How would you handle the input?"

**What they're testing:** Production thinking — validation, error handling, defensive coding.

### The 5 things to address:

#### 1. Input Parsing (JSON → Object)
```java
public Expense parseExpense(String json) {
    // Use a parser (Jackson, Gson, or manual)
    Map<String, Object> map = JsonParser.parse(json);
    
    // Extract with type safety
    String id = getString(map, "expense_id");
    double amount = getDouble(map, "amount_usd");
    // ...
}
```

#### 2. Input Validation (don't let bad data crash your system)
```java
public List<String> validate(Expense expense) {
    List<String> errors = new ArrayList<>();
    
    if (expense.getId() == null || expense.getId().isBlank())
        errors.add("expense_id is required");
    
    if (expense.getAmount() < 0)
        errors.add("amount cannot be negative");
    
    if (expense.getAmount() > 1_000_000)
        errors.add("amount exceeds maximum allowed");
    
    if (!VALID_TYPES.contains(expense.getType()))
        errors.add("unknown expense_type: " + expense.getType());
    
    return errors; // empty = valid
}
```

#### 3. Error Handling (graceful, informative)
```java
public ApiResponse processExpense(String json) {
    try {
        Expense expense = parseExpense(json);
        List<String> errors = validate(expense);
        
        if (!errors.isEmpty()) {
            return ApiResponse.badRequest(errors);  // 400
        }
        
        Result result = service.process(expense);
        return ApiResponse.success(result);          // 200
        
    } catch (JsonParseException e) {
        return ApiResponse.badRequest("Malformed JSON"); // 400
    } catch (Exception e) {
        log.error("Unexpected error", e);
        return ApiResponse.serverError();                // 500
    }
}
```

#### 4. Idempotency (same request twice = same result)
```java
// Use expense_id as idempotency key
public Result process(Expense expense) {
    if (processedIds.contains(expense.getId())) {
        return getExistingResult(expense.getId()); // don't double-process
    }
    // ... process
    processedIds.add(expense.getId());
}
```

#### 5. Rate Limiting / Bounds
- Max list size (don't accept 10M expenses in one call)
- Max string lengths
- Timeout on processing

### Key phrases to say:
- "I'd validate all inputs before processing"
- "I'd return structured errors so the caller knows what to fix"
- "I'd make this idempotent using the expense_id"
- "I'd cap the list size to prevent OOM"

---

## Stage 3: LLD with Design Patterns

**Interviewer says something like:**
> "Now we want to add more rule types in the future. How would you design this to be extensible?"

**What they're testing:** SOLID principles, design patterns, clean architecture.

### The Playbook:

#### Step 1: Identify what varies
Ask yourself: "What will change or grow over time?"
- New rule types → **Strategy Pattern**
- Object creation complexity → **Factory Pattern**
- Notifications to multiple listeners → **Observer Pattern**
- Complex object construction → **Builder Pattern**

#### Step 2: Apply Strategy Pattern (most common)
```java
// The interface
public interface Rule {
    String getRuleId();
    List<Violation> evaluate(List<Expense> expenses);
}

// Concrete strategies
public class MaxAmountRule implements Rule { ... }
public class BanRule implements Rule { ... }
public class TripTotalRule implements Rule { ... }

// The engine (never changes when new rules are added)
public class RuleEngine {
    public List<Violation> evaluate(List<Rule> rules, List<Expense> expenses) {
        List<Violation> violations = new ArrayList<>();
        for (Rule rule : rules) {
            violations.addAll(rule.evaluate(expenses));
        }
        return violations;
    }
}
```

#### Step 3: Add Factory if rule creation is complex
```java
public class RuleFactory {
    public static Rule create(Map<String, String> config) {
        String type = config.get("type");
        switch (type) {
            case "ban":
                return new BanRule(config.get("id"), config.get("field"), config.get("value"));
            case "max_amount":
                return new MaxAmountRule(config.get("id"), Double.parseDouble(config.get("limit")));
            case "trip_total":
                return new TripTotalRule(config.get("id"), Double.parseDouble(config.get("limit")));
            default:
                throw new IllegalArgumentException("Unknown rule type: " + type);
        }
    }
}
```

#### Step 4: Show the full flow
```java
// 1. Parse rules from config/API
List<Rule> rules = ruleConfigs.stream()
    .map(RuleFactory::create)
    .collect(toList());

// 2. Parse and validate expenses
List<Expense> expenses = parseAndValidate(rawInput);

// 3. Evaluate
List<Violation> violations = engine.evaluate(rules, expenses);

// 4. Return structured response
return new EvaluationResult(expenses.size(), violations);
```

---

## Design Patterns Cheat Sheet (for interviews)

| Pattern | When to use | Interview signal |
|---------|-------------|-----------------|
| **Strategy** | Multiple algorithms, pick at runtime | "Add more types in future" |
| **Factory** | Complex object creation, decouple caller from concrete class | "Rules come from config/API" |
| **Observer** | Notify multiple components of changes | "Alert, log, and update dashboard" |
| **Builder** | Object with many optional params | "Complex configuration" |
| **State** | Object behavior changes based on state | "ATM states", "Order lifecycle" |
| **Decorator** | Add behavior without modifying class | "Add logging, caching, retry" |
| **Singleton** | One instance globally | "Connection pool", "Config" |

---

## SOLID Principles — What to Say

| Principle | One-liner | How to show it |
|-----------|-----------|----------------|
| **S** - Single Responsibility | "Each class does one thing" | Separate Rule, Engine, Validator |
| **O** - Open/Closed | "Open for extension, closed for modification" | New Rule = new class, Engine unchanged |
| **L** - Liskov Substitution | "Any Rule subtype works in the engine" | All rules implement same interface |
| **I** - Interface Segregation | "Don't force unused methods" | Rule interface is minimal |
| **D** - Dependency Inversion | "Depend on abstractions" | Engine depends on Rule interface, not concrete rules |

---

## Common Interview Progressions

### Expense/Rules Engine (Amazon favorite)
```
Parse expense → Validate fields → Evaluate rules → Strategy pattern for rules
```

### Delivery/Ride System
```
Calculate cost → Handle overlaps (sweep line) → Payment tracking → Analytics
```

### Booking System (Movie/Hotel/Flight)
```
Search availability → Book with concurrency → Payment → Cancellation policy
```

### Rate Limiter
```
Fixed window counter → Sliding window → Token bucket → Strategy pattern for algorithms
```

---

## What Interviewers Want to Hear

### During Stage 1:
- "Let me clarify the input format"
- "Edge case: what if the list is empty?"
- "This is O(N log N) time, O(N) space"

### During Stage 2:
- "I'd validate before processing to fail fast"
- "I'd return structured errors, not just throw"
- "I'd cap input size to prevent abuse"
- "I'd make it idempotent"

### During Stage 3:
- "This should be extensible — I'll use Strategy pattern"
- "New rule types shouldn't require changing the engine"
- "The factory can create rules from API/config input"
- "Each rule is independently testable"

---

## Anti-Patterns to Avoid

1. **God class** — Don't put everything in one class
2. **Switch on type** — Use polymorphism instead
3. **Stringly typed** — Use enums, not raw strings for known values
4. **No validation** — Always validate at the boundary
5. **Catching Exception** — Catch specific exceptions
6. **Returning null** — Return empty collections or Optional
7. **Premature optimization** — Get it correct first, optimize if asked

---

## Template: How to Structure Your Answer

```
1. "Let me start with the core entities" (2 min)
   → Expense, Rule, Violation

2. "Here's the interface for extensibility" (2 min)
   → Rule interface with evaluate()

3. "Let me implement the concrete rules" (10 min)
   → BanRule, MaxAmountRule, etc.

4. "Here's the engine that ties it together" (5 min)
   → RuleEngine.evaluateRules()

5. "For the API layer, I'd add validation" (5 min)
   → Input parsing, error handling

6. "To support future rule creation via API" (3 min)
   → RuleFactory from config
```

This structure shows you think in layers and can build incrementally — exactly what they want to see.
