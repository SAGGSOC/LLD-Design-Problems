import java.util.*;

/**
 * Minimalistic JSON Parser — interview-ready, single-file.
 *
 * Two-phase: Lexer (chars → tokens) + Parser (tokens → AST).
 * Handles: objects, arrays, strings (with escapes), numbers, booleans, null.
 * Grammar: LL(1) recursive descent, O(n) time, O(depth) stack.
 */
public class JsonParser {

    // ─────────────────────── AST Nodes ───────────────────────

    static abstract class JsonValue {}

    static class JsonObject extends JsonValue {
        final Map<String, JsonValue> map = new LinkedHashMap<>();
        public String toString() { return map.toString(); }
    }

    static class JsonArray extends JsonValue {
        final List<JsonValue> list = new ArrayList<>();
        public String toString() { return list.toString(); }
    }

    static class JsonString extends JsonValue {
        final String value;
        JsonString(String v) { this.value = v; }
        public String toString() { return "\"" + value + "\""; }
    }

    static class JsonNumber extends JsonValue {
        final double value;
        JsonNumber(double v) { this.value = v; }
        public String toString() { return String.valueOf(value); }
    }

    static class JsonBool extends JsonValue {
        final boolean value;
        JsonBool(boolean v) { this.value = v; }
        public String toString() { return String.valueOf(value); }
    }

    static class JsonNull extends JsonValue {
        static final JsonNull INSTANCE = new JsonNull();
        public String toString() { return "null"; }
    }

    // ─────────────────────── Token ───────────────────────

    enum TokenType {
        LBRACE, RBRACE, LBRACKET, RBRACKET, COLON, COMMA,
        STRING, NUMBER, TRUE, FALSE, NULL, EOF
    }

    static class Token {
        final TokenType type;
        final String value;
        Token(TokenType t, String v) { this.type = t; this.value = v; }
    }

    // ─────────────────────── Lexer ───────────────────────

    static class Lexer {
        private final String input;
        private int pos;

        Lexer(String input) { this.input = input; this.pos = 0; }

        Token next() {
            skipWS();
            if (pos >= input.length()) return new Token(TokenType.EOF, "");

            char ch = input.charAt(pos);
            switch (ch) {
                case '{': pos++; return new Token(TokenType.LBRACE, "{");
                case '}': pos++; return new Token(TokenType.RBRACE, "}");
                case '[': pos++; return new Token(TokenType.LBRACKET, "[");
                case ']': pos++; return new Token(TokenType.RBRACKET, "]");
                case ':': pos++; return new Token(TokenType.COLON, ":");
                case ',': pos++; return new Token(TokenType.COMMA, ",");
                case '"': return readString();
                case 't': return keyword("true", TokenType.TRUE);
                case 'f': return keyword("false", TokenType.FALSE);
                case 'n': return keyword("null", TokenType.NULL);
                default:
                    if (ch == '-' || Character.isDigit(ch)) return readNumber();
                    throw error("Unexpected char '" + ch + "'");
            }
        }

        private void skipWS() {
            while (pos < input.length() && " \t\n\r".indexOf(input.charAt(pos)) >= 0) pos++;
        }

        private Token readString() {
            pos++; // skip opening "
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (ch == '"') { pos++; return new Token(TokenType.STRING, sb.toString()); }
                if (ch == '\\') {
                    pos++;
                    char esc = input.charAt(pos);
                    switch (esc) {
                        case '"': case '\\': case '/': sb.append(esc); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(input.substring(pos+1, pos+5), 16));
                            pos += 4; break;
                        default: throw error("Bad escape \\" + esc);
                    }
                } else {
                    sb.append(ch);
                }
                pos++;
            }
            throw error("Unterminated string");
        }

        private Token readNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            return new Token(TokenType.NUMBER, input.substring(start, pos));
        }

        private Token keyword(String kw, TokenType type) {
            if (input.startsWith(kw, pos)) { pos += kw.length(); return new Token(type, kw); }
            throw error("Unexpected token");
        }

        private RuntimeException error(String msg) {
            return new RuntimeException(msg + " at position " + pos);
        }
    }

    // ─────────────────────── Parser (Recursive Descent) ───────────────────────

    static class Parser {
        private final Lexer lexer;
        private Token cur;

        Parser(Lexer lexer) {
            this.lexer = lexer;
            this.cur = lexer.next();
        }

        JsonValue parse() {
            JsonValue val = parseValue();
            expect(TokenType.EOF);
            return val;
        }

        // value → object | array | string | number | true | false | null
        private JsonValue parseValue() {
            switch (cur.type) {
                case LBRACE:   return parseObject();
                case LBRACKET: return parseArray();
                case STRING:   return parseString();
                case NUMBER:   return parseNumber();
                case TRUE:     advance(); return new JsonBool(true);
                case FALSE:    advance(); return new JsonBool(false);
                case NULL:     advance(); return JsonNull.INSTANCE;
                default: throw new RuntimeException("Unexpected " + cur.type);
            }
        }

        // object → '{' (pair (',' pair)*)? '}'
        private JsonObject parseObject() {
            JsonObject obj = new JsonObject();
            expect(TokenType.LBRACE);
            if (cur.type != TokenType.RBRACE) {
                parsePair(obj);
                while (cur.type == TokenType.COMMA) { advance(); parsePair(obj); }
            }
            expect(TokenType.RBRACE);
            return obj;
        }

        // pair → string ':' value
        private void parsePair(JsonObject obj) {
            String key = cur.value;
            expect(TokenType.STRING);
            expect(TokenType.COLON);
            obj.map.put(key, parseValue());
        }

        // array → '[' (value (',' value)*)? ']'
        private JsonArray parseArray() {
            JsonArray arr = new JsonArray();
            expect(TokenType.LBRACKET);
            if (cur.type != TokenType.RBRACKET) {
                arr.list.add(parseValue());
                while (cur.type == TokenType.COMMA) { advance(); arr.list.add(parseValue()); }
            }
            expect(TokenType.RBRACKET);
            return arr;
        }

        private JsonString parseString() {
            String v = cur.value; advance(); return new JsonString(v);
        }

        private JsonNumber parseNumber() {
            double v = Double.parseDouble(cur.value); advance(); return new JsonNumber(v);
        }

        private void advance() { cur = lexer.next(); }

        private void expect(TokenType type) {
            if (cur.type != type)
                throw new RuntimeException("Expected " + type + ", got " + cur.type);
            advance();
        }
    }

    // ─────────────────────── Public API ───────────────────────

    public static JsonValue parse(String input) {
        return new Parser(new Lexer(input)).parse();
    }

    // ─────────────────────── Demo ───────────────────────

    public static void main(String[] args) {
        String json = "{\"name\": \"Alice\", \"age\": 30, \"scores\": [95, 87, null], \"active\": true}";

        JsonValue root = parse(json);
        System.out.println(root);

        // Type-safe access
        JsonObject obj = (JsonObject) root;
        String name    = ((JsonString) obj.map.get("name")).value;
        double age     = ((JsonNumber) obj.map.get("age")).value;
        boolean active = ((JsonBool) obj.map.get("active")).value;
        JsonArray scores = (JsonArray) obj.map.get("scores");

        System.out.println("Name: " + name);
        System.out.println("Age: " + (int) age);
        System.out.println("Active: " + active);
        System.out.println("Scores: " + scores);
        System.out.println("Score[2] is null: " + (scores.list.get(2) instanceof JsonNull));
    }
}
