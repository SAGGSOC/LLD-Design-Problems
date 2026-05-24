# JSON Parser — Low-Level Design & Java Implementation

---

## 1. Problem Statement

Design and implement a JSON parser that takes a raw JSON string and produces a structured in-memory representation (tree of objects, arrays, strings, numbers, booleans, and null).

```
Input:  '{"name": "Alice", "age": 30, "scores": [95, 87, null], "active": true}'

Output: A tree structure:
  JsonObject {
    "name"   → JsonString("Alice")
    "age"    → JsonNumber(30)
    "scores" → JsonArray [JsonNumber(95), JsonNumber(87), JsonNull]
    "active" → JsonBool(true)
  }
```

---

## 2. JSON Grammar (RFC 8259)

```
json       → value

value      → object | array | string | number | "true" | "false" | "null"

object     → '{' '}'
           | '{' members '}'

members    → pair (',' pair)*

pair       → string ':' value

array      → '[' ']'
           | '[' elements ']'

elements   → value (',' value)*

string     → '"' characters '"'

characters → (any unicode char except " and \ ) | escape_sequence

escape_seq → '\"' | '\\' | '\/' | '\b' | '\f' | '\n' | '\r' | '\t' | '\uXXXX'

number     → integer fraction? exponent?
integer    → '-'? ('0' | [1-9][0-9]*)
fraction   → '.' [0-9]+
exponent   → ('e'|'E') ('+'|'-')? [0-9]+
```

This is an LL(1) context-free grammar — each production rule maps directly to a method in a recursive descent parser. You can always decide which rule to apply by looking at the next single character.

---

## 3. Architecture: Two-Phase Design

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Raw JSON    │────▶│   Lexer      │────▶│   Parser     │────▶  JsonValue (AST)
│  String      │     │  (Tokenizer) │     │  (Recursive  │
│              │     │              │     │   Descent)   │
└──────────────┘     └──────────────┘     └──────────────┘

Phase 1: Lexer         Phase 2: Parser
"chars → tokens"       "tokens → tree"
```

Separation of concerns: the Lexer handles character-level details (whitespace, escape sequences, number formats). The Parser handles structure (nesting, grammar rules). Each is independently testable.

---

## 4. Class Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                          JsonParser (facade)                        │
│─────────────────────────────────────────────────────────────────────│
│ + parse(input: String): JsonValue                                   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ uses
              ┌────────────────┼────────────────┐
              ▼                                 ▼
┌──────────────────────┐             ┌──────────────────────┐
│       Lexer          │             │      Parser          │
│──────────────────────│             │──────────────────────│
│ - input: String      │             │ - lexer: Lexer       │
│ - pos: int           │             │ - current: Token     │
│──────────────────────│             │──────────────────────│
│ + nextToken(): Token │             │ + parse(): JsonValue │
│ - skipWhitespace()   │◀───────────│ - parseValue()       │
│ - readString()       │  consumes  │ - parseObject()      │
│ - readNumber()       │  tokens    │ - parseArray()       │
│ - readKeyword()      │             │ - parsePair()        │
└──────────────────────┘             │ - advance()          │
                                     │ - expect(TokenType)  │
                                     └──────────────────────┘

┌──────────────────────┐
│     Token            │
│──────────────────────│
│ + type: TokenType    │
│ + value: String      │
│ + position: int      │
└──────────────────────┘

┌──────────────────────┐
│  «enum» TokenType    │
│──────────────────────│
│  LEFT_BRACE          │
│  RIGHT_BRACE         │
│  LEFT_BRACKET        │
│  RIGHT_BRACKET       │
│  COLON               │
│  COMMA               │
│  STRING              │
│  NUMBER              │
│  TRUE                │
│  FALSE               │
│  NULL                │
│  EOF                 │
└──────────────────────┘

                    ┌──────────────────┐
                    │  «abstract»      │
                    │   JsonValue      │
                    │──────────────────│
                    │ + getType()      │
                    └────────┬─────────┘
                             │
        ┌──────────┬─────────┼──────────┬──────────┬──────────┐
        ▼          ▼         ▼          ▼          ▼          ▼
  ┌──────────┐┌──────────┐┌──────────┐┌──────────┐┌──────────┐┌──────────┐
  │JsonObject││JsonArray ││JsonString││JsonNumber││JsonBool  ││JsonNull  │
  │──────────││──────────││──────────││──────────││──────────││──────────│
  │Map<String││List<Json ││String    ││double    ││boolean   ││          │
  │,JsonValue││Value>    ││value     ││value     ││value     ││          │
  │> members ││elements  ││          ││          ││          ││          │
  └──────────┘└──────────┘└──────────┘└──────────┘└──────────┘└──────────┘
```

---

## 5. Java Implementation

### 5.1 Token & TokenType

```java
public enum TokenType {
    LEFT_BRACE, RIGHT_BRACE,       // { }
    LEFT_BRACKET, RIGHT_BRACKET,   // [ ]
    COLON, COMMA,                  // : ,
    STRING, NUMBER,
    TRUE, FALSE, NULL,
    EOF
}
```

```java
public class Token {
    private final TokenType type;
    private final String value;
    private final int position;

    public Token(TokenType type, String value, int position) {
        this.type = type;
        this.value = value;
        this.position = position;
    }

    public TokenType getType()  { return type; }
    public String getValue()    { return value; }
    public int getPosition()    { return position; }
}
```


### 5.2 JsonValue Hierarchy (AST Nodes)

```java
public abstract class JsonValue {
    public enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }
    public abstract Type getType();
}
```

```java
public class JsonObject extends JsonValue {
    private final Map<String, JsonValue> members = new LinkedHashMap<>();

    public Type getType() { return Type.OBJECT; }

    public void put(String key, JsonValue value) { members.put(key, value); }
    public JsonValue get(String key)             { return members.get(key); }
    public Map<String, JsonValue> getMembers()   { return members; }
}
```

```java
public class JsonArray extends JsonValue {
    private final List<JsonValue> elements = new ArrayList<>();

    public Type getType() { return Type.ARRAY; }

    public void add(JsonValue value)        { elements.add(value); }
    public JsonValue get(int index)         { return elements.get(index); }
    public List<JsonValue> getElements()    { return elements; }
    public int size()                       { return elements.size(); }
}
```

```java
public class JsonString extends JsonValue {
    private final String value;

    public JsonString(String value) { this.value = value; }
    public Type getType()           { return Type.STRING; }
    public String getValue()        { return value; }
}
```

```java
public class JsonNumber extends JsonValue {
    private final double value;

    public JsonNumber(double value) { this.value = value; }
    public Type getType()           { return Type.NUMBER; }
    public double getValue()        { return value; }
    public int intValue()           { return (int) value; }
    public long longValue()         { return (long) value; }
}
```

```java
public class JsonBool extends JsonValue {
    private final boolean value;

    public JsonBool(boolean value) { this.value = value; }
    public Type getType()          { return Type.BOOLEAN; }
    public boolean getValue()      { return value; }
}
```

```java
public class JsonNull extends JsonValue {
    public static final JsonNull INSTANCE = new JsonNull();

    public Type getType() { return Type.NULL; }
}
```

### 5.3 ParseException

```java
public class ParseException extends RuntimeException {
    private final int position;

    public ParseException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    public int getPosition() { return position; }
}
```

### 5.4 Lexer (Tokenizer)

```java
public class Lexer {
    private final String input;
    private int pos;

    public Lexer(String input) {
        this.input = input;
        this.pos = 0;
    }

    public Token nextToken() {
        skipWhitespace();

        if (pos >= input.length()) {
            return new Token(TokenType.EOF, "", pos);
        }

        char ch = input.charAt(pos);

        switch (ch) {
            case '{': pos++; return new Token(TokenType.LEFT_BRACE, "{", pos - 1);
            case '}': pos++; return new Token(TokenType.RIGHT_BRACE, "}", pos - 1);
            case '[': pos++; return new Token(TokenType.LEFT_BRACKET, "[", pos - 1);
            case ']': pos++; return new Token(TokenType.RIGHT_BRACKET, "]", pos - 1);
            case ':': pos++; return new Token(TokenType.COLON, ":", pos - 1);
            case ',': pos++; return new Token(TokenType.COMMA, ",", pos - 1);
            case '"': return readString();
            case 't': return readKeyword("true", TokenType.TRUE);
            case 'f': return readKeyword("false", TokenType.FALSE);
            case 'n': return readKeyword("null", TokenType.NULL);
            default:
                if (ch == '-' || Character.isDigit(ch)) {
                    return readNumber();
                }
                throw new ParseException("Unexpected character '" + ch + "'", pos);
        }
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char ch = input.charAt(pos);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Token readString() {
        int start = pos;
        pos++; // skip opening "
        StringBuilder sb = new StringBuilder();

        while (pos < input.length()) {
            char ch = input.charAt(pos);

            if (ch == '"') {
                pos++; // skip closing "
                return new Token(TokenType.STRING, sb.toString(), start);
            }

            if (ch == '\\') {
                pos++;
                if (pos >= input.length()) {
                    throw new ParseException("Unexpected end of input in escape", pos);
                }
                char esc = input.charAt(pos);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 >= input.length()) {
                            throw new ParseException("Incomplete unicode escape", pos);
                        }
                        String hex = input.substring(pos + 1, pos + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default:
                        throw new ParseException("Invalid escape '\\" + esc + "'", pos);
                }
            } else {
                sb.append(ch);
            }
            pos++;
        }

        throw new ParseException("Unterminated string", start);
    }

    private Token readNumber() {
        int start = pos;

        // optional minus
        if (peek() == '-') pos++;

        // integer part
        if (peek() == '0') {
            pos++;
        } else if (Character.isDigit(peek())) {
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        } else {
            throw new ParseException("Invalid number", pos);
        }

        // fraction
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                throw new ParseException("Expected digit after '.'", pos);
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }

        // exponent
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                pos++;
            }
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                throw new ParseException("Expected digit in exponent", pos);
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }

        return new Token(TokenType.NUMBER, input.substring(start, pos), start);
    }

    private Token readKeyword(String keyword, TokenType type) {
        int start = pos;
        if (input.startsWith(keyword, pos)) {
            pos += keyword.length();
            return new Token(type, keyword, start);
        }
        throw new ParseException("Unexpected token", pos);
    }

    private char peek() {
        if (pos >= input.length()) {
            throw new ParseException("Unexpected end of input", pos);
        }
        return input.charAt(pos);
    }
}
```


### 5.5 Parser (Recursive Descent)

Each grammar rule maps to one method. The parser consumes tokens from the Lexer and builds the AST.

```java
public class Parser {
    private final Lexer lexer;
    private Token current;
    private int depth;

    private static final int MAX_DEPTH = 512;

    public Parser(Lexer lexer) {
        this.lexer = lexer;
        this.current = lexer.nextToken();
        this.depth = 0;
    }

    // ─── Entry point ───

    public JsonValue parse() {
        JsonValue value = parseValue();
        expect(TokenType.EOF);
        return value;
    }

    // ─── value → object | array | string | number | true | false | null ───

    private JsonValue parseValue() {
        depth++;
        if (depth > MAX_DEPTH) {
            throw new ParseException("Maximum nesting depth exceeded", current.getPosition());
        }

        JsonValue result;

        switch (current.getType()) {
            case LEFT_BRACE:   result = parseObject();  break;
            case LEFT_BRACKET: result = parseArray();   break;
            case STRING:       result = parseString();  break;
            case NUMBER:       result = parseNumber();  break;
            case TRUE:         advance(); result = new JsonBool(true);    break;
            case FALSE:        advance(); result = new JsonBool(false);   break;
            case NULL:         advance(); result = JsonNull.INSTANCE;     break;
            default:
                throw new ParseException(
                    "Unexpected token " + current.getType(), current.getPosition()
                );
        }

        depth--;
        return result;
    }

    // ─── object → '{' (pair (',' pair)*)? '}' ───

    private JsonObject parseObject() {
        JsonObject obj = new JsonObject();
        expect(TokenType.LEFT_BRACE);

        if (current.getType() != TokenType.RIGHT_BRACE) {
            parsePair(obj);
            while (current.getType() == TokenType.COMMA) {
                advance(); // consume ','
                parsePair(obj);
            }
        }

        expect(TokenType.RIGHT_BRACE);
        return obj;
    }

    // ─── pair → string ':' value ───

    private void parsePair(JsonObject obj) {
        if (current.getType() != TokenType.STRING) {
            throw new ParseException(
                "Expected string key, got " + current.getType(), current.getPosition()
            );
        }
        String key = current.getValue();
        advance();
        expect(TokenType.COLON);
        JsonValue value = parseValue();
        obj.put(key, value);
    }

    // ─── array → '[' (value (',' value)*)? ']' ───

    private JsonArray parseArray() {
        JsonArray arr = new JsonArray();
        expect(TokenType.LEFT_BRACKET);

        if (current.getType() != TokenType.RIGHT_BRACKET) {
            arr.add(parseValue());
            while (current.getType() == TokenType.COMMA) {
                advance(); // consume ','
                arr.add(parseValue());
            }
        }

        expect(TokenType.RIGHT_BRACKET);
        return arr;
    }

    // ─── Primitives ───

    private JsonString parseString() {
        String value = current.getValue();
        advance();
        return new JsonString(value);
    }

    private JsonNumber parseNumber() {
        double value = Double.parseDouble(current.getValue());
        advance();
        return new JsonNumber(value);
    }

    // ─── Helpers ───

    private void advance() {
        current = lexer.nextToken();
    }

    private void expect(TokenType type) {
        if (current.getType() != type) {
            throw new ParseException(
                "Expected " + type + ", got " + current.getType(),
                current.getPosition()
            );
        }
        advance();
    }
}
```

### 5.6 Facade

```java
public class JsonParser {

    public static JsonValue parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new ParseException("Empty input", 0);
        }
        Lexer lexer = new Lexer(input);
        Parser parser = new Parser(lexer);
        return parser.parse();
    }
}
```

---

## 6. Execution Trace

```
Input: '{"a": [1, true]}'

Lexer produces tokens:
  LEFT_BRACE → STRING("a") → COLON → LEFT_BRACKET → NUMBER("1")
  → COMMA → TRUE → RIGHT_BRACKET → RIGHT_BRACE → EOF

Parser call stack:

  parse()
    parseValue()                → sees LEFT_BRACE
      parseObject()             → consumes {
        parsePair(obj)
          key = "a"             → consumes STRING
          expect(COLON)         → consumes :
          parseValue()          → sees LEFT_BRACKET
            parseArray()        → consumes [
              parseValue()      → sees NUMBER → JsonNumber(1.0)
              sees COMMA        → consumes ,
              parseValue()      → sees TRUE → JsonBool(true)
            expect(])           → consumes ]
          → returns JsonArray([JsonNumber(1.0), JsonBool(true)])
        → obj.put("a", JsonArray)
      expect(})                 → consumes }
    → returns JsonObject({"a": [1, true]})
  expect(EOF)                   → done

Result:
  JsonObject {
    "a" → JsonArray [
      JsonNumber(1.0),
      JsonBool(true)
    ]
  }
```

---

## 7. Usage Example

```java
public class Main {
    public static void main(String[] args) {
        String json = "{\"name\": \"Alice\", \"age\": 30, \"scores\": [95, 87, null], \"active\": true}";

        JsonValue root = JsonParser.parse(json);

        // root is a JsonObject
        JsonObject obj = (JsonObject) root;

        // access fields
        String name = ((JsonString) obj.get("name")).getValue();       // "Alice"
        double age  = ((JsonNumber) obj.get("age")).getValue();        // 30.0
        boolean active = ((JsonBool) obj.get("active")).getValue();    // true

        // access array
        JsonArray scores = (JsonArray) obj.get("scores");
        double first = ((JsonNumber) scores.get(0)).getValue();        // 95.0
        JsonValue third = scores.get(2);                               // JsonNull.INSTANCE

        System.out.println("Name: " + name);
        System.out.println("Age: " + (int) age);
        System.out.println("First score: " + (int) first);
        System.out.println("Third score is null: " + (third instanceof JsonNull));
        System.out.println("Active: " + active);
    }
}
```

---

## 8. Error Handling

| Input | Error Message |
|---|---|
| `{"key": }` | Unexpected token RIGHT_BRACE at position 8 — expected a value |
| `{"key" "value"}` | Expected COLON, got STRING at position 7 |
| `[1, 2,]` | Unexpected token RIGHT_BRACKET at position 7 |
| `"hello` | Unterminated string at position 0 |
| `01234` | Leading zeros not allowed (Lexer rejects after reading `0` then seeing `1`) |
| `{'key': 'val'}` | Unexpected character `'` at position 1 |
| `undefined` | Unexpected token at position 0 |
| `"\x41"` | Invalid escape `\x` at position 2 |

---

## 9. Complexity Analysis

| Metric | Value |
|---|---|
| Time complexity | O(n) — each character visited exactly once, no backtracking |
| Space complexity | O(n) for output AST + O(d) call stack where d = nesting depth |
| Nesting depth limit | 512 (configurable via MAX_DEPTH) |
| Grammar class | LL(1) — single token lookahead determines production rule |

The Lexer advances `pos` forward monotonically. The Parser consumes each token exactly once. No lookahead beyond 1 token.

---

## 10. Design Decisions & Trade-offs

| Decision | Trade-off |
|---|---|
| Two-phase (Lexer + Parser) vs single-pass | Two-phase is cleaner, independently testable. Single-pass is simpler for whiteboard interviews. |
| Custom AST nodes vs `Map`/`List` | AST preserves type info (`JsonNumber` vs `JsonString`). Native types are more convenient for callers. |
| `double` for numbers vs `BigDecimal` | `double` is fast but loses precision for very large integers. `BigDecimal` is exact but slower. |
| Strict RFC 8259 vs lenient | Strict rejects trailing commas, comments, single quotes. Lenient is friendlier but non-standard. |
| Singleton `JsonNull.INSTANCE` | Avoids allocating a new object for every `null` — there's only one null value. |
| `LinkedHashMap` for objects | Preserves insertion order of keys (matches JSON source order). `HashMap` would be faster but unordered. |
| MAX_DEPTH = 512 | Prevents stack overflow from deeply nested input (DoS protection). Configurable per use case. |

---

## 11. Edge Cases

### Unicode Escapes
```
Input:  "Hello \u0041\u0042"
Output: "Hello AB"              (U+0041 = 'A', U+0042 = 'B')
```

### Number Edge Cases
```
Valid:    0, -0, 0.5, -0.5, 1e10, 1E10, 1e+10, 1e-10, 1.5e2
Invalid:  01, +1, .5, 1., 1e, NaN, Infinity, 0x1F
```

### Deeply Nested Input (DoS)
```
Input: [[[[[[...512 levels...]]]]]]]
→ ParseException: "Maximum nesting depth exceeded"
```

### Empty Structures
```
{}    → JsonObject with empty members map
[]    → JsonArray with empty elements list
```

---

## 12. Extension: Streaming / SAX-Style Parser

For very large JSON files (GBs), a SAX-style event-driven parser avoids loading the entire tree into memory.

```java
public interface JsonHandler {
    void onObjectStart();
    void onObjectKey(String key);
    void onObjectEnd();
    void onArrayStart();
    void onArrayEnd();
    void onString(String value);
    void onNumber(double value);
    void onBoolean(boolean value);
    void onNull();
}
```

```
Input: {"users": [{"name": "Alice"}]}

Events emitted:
  onObjectStart()
  onObjectKey("users")
  onArrayStart()
    onObjectStart()
    onObjectKey("name")
    onString("Alice")
    onObjectEnd()
  onArrayEnd()
  onObjectEnd()
```

Memory: O(depth) instead of O(n). This is the approach used by Jackson's `JsonParser` (streaming API) and Gson's `JsonReader`.

---

## 13. Class Summary

```
┌─────────────────────────────────────────────────────────────┐
│  Package: com.parser.json                                    │
├─────────────────────────────────────────────────────────────┤
│  JsonParser          → static facade, entry point            │
│  Lexer               → char[] → Token stream                 │
│  Parser              → Token stream → JsonValue AST          │
│  Token               → (type, value, position)               │
│  TokenType           → enum of 12 token types                │
│  ParseException      → error with position info              │
│  JsonValue           → abstract base (6 subtypes)            │
│  JsonObject          → LinkedHashMap<String, JsonValue>      │
│  JsonArray           → ArrayList<JsonValue>                   │
│  JsonString          → wraps String                          │
│  JsonNumber          → wraps double                          │
│  JsonBool            → wraps boolean                         │
│  JsonNull            → singleton INSTANCE                    │
│  JsonHandler         → SAX-style callback interface          │
└─────────────────────────────────────────────────────────────┘
```

