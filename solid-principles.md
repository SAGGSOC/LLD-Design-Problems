# SOLID Principles — Interview-Ready Guide

SOLID is a set of five design principles that help you write code that's easy to maintain, extend, and test. Robert C. Martin (Uncle Bob) introduced them, and they show up in almost every OOP design interview.

---

## 1. S — Single Responsibility Principle (SRP)

> "A class should have only one reason to change."

Every class should do one thing and do it well. If a class has two responsibilities, a change in one can break the other.

### Bad Example

```java
class Employee {
    String name;
    double salary;

    double calculatePay() {
        // tax logic, overtime logic, bonus logic
        return salary * 1.2;
    }

    void saveToDatabase() {
        // JDBC connection, SQL insert
        System.out.println("INSERT INTO employees ...");
    }

    String generatePaySlip() {
        // PDF formatting, layout, headers
        return "PaySlip for " + name;
    }
}
```

This class has THREE reasons to change:
1. Pay calculation rules change
2. Database schema changes
3. PaySlip format changes

### Good Example

```java
class Employee {
    String name;
    double salary;
}

class PayCalculator {
    double calculatePay(Employee emp) {
        return emp.salary * 1.2;
    }
}

class EmployeeRepository {
    void save(Employee emp) {
        System.out.println("INSERT INTO employees ...");
    }
}

class PaySlipGenerator {
    String generate(Employee emp) {
        return "PaySlip for " + emp.name;
    }
}
```

Now each class has exactly one reason to change. The `Employee` class is just data. Pay logic, persistence, and formatting are each isolated.

### Interview One-Liner
"If you have to describe what a class does using the word AND, it probably violates SRP."

---

## 2. O — Open/Closed Principle (OCP)

> "Software entities should be open for extension, but closed for modification."

You should be able to add new behavior WITHOUT changing existing code. This is typically achieved through abstraction (interfaces/abstract classes) and polymorphism.

### Bad Example

```java
class DiscountCalculator {
    double calculate(String customerType, double amount) {
        if (customerType.equals("REGULAR")) {
            return amount * 0.1;
        } else if (customerType.equals("PREMIUM")) {
            return amount * 0.2;
        } else if (customerType.equals("VIP")) {
            return amount * 0.3;
        }
        // Every new customer type = modify this class
        return 0;
    }
}
```

Adding a "GOLD" customer means editing this class. Every edit risks breaking existing logic.

### Good Example

```java
interface DiscountStrategy {
    double calculate(double amount);
}

class RegularDiscount implements DiscountStrategy {
    public double calculate(double amount) { return amount * 0.1; }
}

class PremiumDiscount implements DiscountStrategy {
    public double calculate(double amount) { return amount * 0.2; }
}

class VIPDiscount implements DiscountStrategy {
    public double calculate(double amount) { return amount * 0.3; }
}

// Adding GOLD? Just create a new class. No existing code touched.
class GoldDiscount implements DiscountStrategy {
    public double calculate(double amount) { return amount * 0.25; }
}

class DiscountCalculator {
    double calculate(DiscountStrategy strategy, double amount) {
        return strategy.calculate(amount);
    }
}
```

Now `DiscountCalculator` is closed for modification but open for extension. New discount types are just new classes.

### Interview One-Liner
"If adding a new feature requires editing existing if-else chains or switch statements, you're violating OCP. Use polymorphism instead."

---

## 3. L — Liskov Substitution Principle (LSP)

> "Subtypes must be substitutable for their base types without altering the correctness of the program."

If class B extends class A, you should be able to use B anywhere A is expected, and nothing should break. The child must honor the contract of the parent.

### Bad Example — The Classic Rectangle-Square Problem

```java
class Rectangle {
    protected int width;
    protected int height;

    void setWidth(int w)  { this.width = w; }
    void setHeight(int h) { this.height = h; }
    int getArea()         { return width * height; }
}

class Square extends Rectangle {
    // A square forces width == height
    void setWidth(int w)  { this.width = w; this.height = w; }
    void setHeight(int h) { this.width = h; this.height = h; }
}
```

Now this breaks:

```java
void resize(Rectangle r) {
    r.setWidth(5);
    r.setHeight(10);
    assert r.getArea() == 50;  // FAILS for Square! Area = 100
}
```

`Square` violates LSP because it changes the behavior the caller expects from `Rectangle`.

### Good Example

```java
interface Shape {
    int getArea();
}

class Rectangle implements Shape {
    private int width, height;
    Rectangle(int w, int h) { this.width = w; this.height = h; }
    public int getArea()    { return width * height; }
}

class Square implements Shape {
    private int side;
    Square(int s)        { this.side = s; }
    public int getArea() { return side * side; }
}
```

No inheritance relationship between Rectangle and Square. Both implement `Shape`. No broken expectations.

### Interview One-Liner
"If a subclass surprises you by behaving differently than the parent in a way that breaks calling code, it violates LSP. Prefer composition or separate interfaces over forced inheritance."

---

## 4. I — Interface Segregation Principle (ISP)

> "Clients should not be forced to depend on interfaces they do not use."

Don't create fat interfaces. If a class only needs 2 out of 10 methods, it shouldn't be forced to implement the other 8.

### Bad Example

```java
interface Worker {
    void code();
    void test();
    void attendMeeting();
    void doManagement();
    void designArchitecture();
}

class JuniorDeveloper implements Worker {
    public void code()               { /* writes code */ }
    public void test()               { /* writes tests */ }
    public void attendMeeting()      { /* attends standup */ }
    public void doManagement()       { /* ??? not my job */ }
    public void designArchitecture() { /* ??? not my job */ }
}
```

`JuniorDeveloper` is forced to implement methods that make no sense for the role.

### Good Example

```java
interface Coder {
    void code();
}

interface Tester {
    void test();
}

interface MeetingAttendee {
    void attendMeeting();
}

interface Manager {
    void doManagement();
}

interface Architect {
    void designArchitecture();
}

class JuniorDeveloper implements Coder, Tester, MeetingAttendee {
    public void code()          { /* writes code */ }
    public void test()          { /* writes tests */ }
    public void attendMeeting() { /* attends standup */ }
}

class TechLead implements Coder, Architect, MeetingAttendee, Manager {
    public void code()               { /* reviews + writes code */ }
    public void designArchitecture() { /* designs systems */ }
    public void attendMeeting()      { /* leads meetings */ }
    public void doManagement()       { /* manages team */ }
}
```

Each class only implements what it actually does. No empty or nonsensical method stubs.

### Interview One-Liner
"If you see empty method implementations or `UnsupportedOperationException` throws, the interface is too fat. Split it."

---

## 5. D — Dependency Inversion Principle (DIP)

> "High-level modules should not depend on low-level modules. Both should depend on abstractions."

Your business logic should not directly instantiate or reference concrete infrastructure classes. Depend on interfaces, and inject the implementation.

### Bad Example

```java
class MySQLDatabase {
    void save(String data) {
        System.out.println("Saving to MySQL: " + data);
    }
}

class OrderService {
    private MySQLDatabase db = new MySQLDatabase();  // tightly coupled

    void placeOrder(String order) {
        // business logic
        db.save(order);
    }
}
```

Problems:
- `OrderService` is welded to MySQL. Switching to PostgreSQL means editing `OrderService`.
- Can't unit test `OrderService` without a real MySQL connection.

### Good Example

```java
interface Database {
    void save(String data);
}

class MySQLDatabase implements Database {
    public void save(String data) {
        System.out.println("Saving to MySQL: " + data);
    }
}

class PostgreSQLDatabase implements Database {
    public void save(String data) {
        System.out.println("Saving to PostgreSQL: " + data);
    }
}

class OrderService {
    private Database db;

    // Dependency injected via constructor
    OrderService(Database db) {
        this.db = db;
    }

    void placeOrder(String order) {
        // business logic
        db.save(order);
    }
}

// Usage
OrderService service = new OrderService(new MySQLDatabase());
// or
OrderService service = new OrderService(new PostgreSQLDatabase());
// or in tests
OrderService service = new OrderService(new MockDatabase());
```

`OrderService` depends on the `Database` abstraction, not on any concrete implementation. Swapping databases or mocking for tests is trivial.

### Interview One-Liner
"If your class uses `new ConcreteClass()` inside its methods, it's probably violating DIP. Inject dependencies through constructors or setters."

---

## Quick Reference Table

| Principle | One-Line Summary | Violation Smell |
|---|---|---|
| SRP | One class, one job | Class described with "AND" |
| OCP | Extend without modifying | Adding features requires editing if-else/switch |
| LSP | Subtypes honor parent contracts | Subclass breaks caller expectations |
| ISP | Small, focused interfaces | Empty method stubs or `UnsupportedOperationException` |
| DIP | Depend on abstractions, not concretions | `new ConcreteClass()` inside business logic |

---

## How They Connect

```
SRP → each class has one job
 └─ makes it easier to follow OCP (small classes are easier to extend)
      └─ extensions via polymorphism must follow LSP (subtypes behave correctly)
           └─ interfaces used for polymorphism should follow ISP (lean interfaces)
                └─ and those interfaces enable DIP (depend on abstractions)
```

They're not independent rules. They reinforce each other. A codebase that follows SRP naturally makes OCP easier, which makes LSP violations less likely, which keeps interfaces lean (ISP), which enables clean dependency injection (DIP).

---

## Real-World Example: Notification System

Putting it all together:

```java
// ISP: Small, focused interfaces
interface MessageSender {
    void send(String to, String body);
}

interface MessageFormatter {
    String format(String template, Map<String, String> vars);
}

// OCP + LSP: New channels = new classes, all substitutable
class EmailSender implements MessageSender {
    public void send(String to, String body) {
        System.out.println("Email to " + to + ": " + body);
    }
}

class SMSSender implements MessageSender {
    public void send(String to, String body) {
        System.out.println("SMS to " + to + ": " + body);
    }
}

class SlackSender implements MessageSender {
    public void send(String to, String body) {
        System.out.println("Slack to " + to + ": " + body);
    }
}

// SRP: Only formats messages
class TemplateFormatter implements MessageFormatter {
    public String format(String template, Map<String, String> vars) {
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}

// SRP: Only orchestrates notification sending
// DIP: Depends on abstractions (MessageSender, MessageFormatter)
class NotificationService {
    private final MessageSender sender;
    private final MessageFormatter formatter;

    NotificationService(MessageSender sender, MessageFormatter formatter) {
        this.sender = sender;
        this.formatter = formatter;
    }

    void notify(String to, String template, Map<String, String> vars) {
        String body = formatter.format(template, vars);
        sender.send(to, body);
    }
}

// Usage — swap implementations freely
NotificationService emailNotifier = new NotificationService(
    new EmailSender(), new TemplateFormatter()
);

NotificationService slackNotifier = new NotificationService(
    new SlackSender(), new TemplateFormatter()
);
```

Every SOLID principle at work:
- SRP: `NotificationService` orchestrates, `TemplateFormatter` formats, senders send
- OCP: Add `PushNotificationSender` without touching existing code
- LSP: All senders are interchangeable wherever `MessageSender` is expected
- ISP: `MessageSender` and `MessageFormatter` are small, focused interfaces
- DIP: `NotificationService` depends on interfaces, not concrete classes
