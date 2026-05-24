import java.util.*;

public class JsonParserSimple {

    private String s;
    private int i;

    public Object parse(String json) {
        this.s = json;
        this.i = 0;
        skipWhitespace();
        return parseValue();
    }

    private Object parseValue() {
        skipWhitespace();

        char c = s.charAt(i);

        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBoolean();
        if (c == 'n') return parseNull();
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new HashMap<>();
        i++; // skip {

        skipWhitespace();
        if (s.charAt(i) == '}') {
            i++;
            return map;
        }

        while (true) {
            skipWhitespace();
            String key = parseString();

            skipWhitespace();
            i++; // skip :

            Object value = parseValue();
            map.put(key, value);

            skipWhitespace();
            char c = s.charAt(i++);

            if (c == '}') break;
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        i++; // skip [

        skipWhitespace();
        if (s.charAt(i) == ']') {
            i++;
            return list;
        }

        while (true) {
            Object val = parseValue();
            list.add(val);

            skipWhitespace();
            char c = s.charAt(i++);

            if (c == ']') break;
        }
        return list;
    }

    private String parseString() {
        i++; // skip "
        StringBuilder sb = new StringBuilder();

        while (s.charAt(i) != '"') {
            sb.append(s.charAt(i++));
        }
        i++; // skip "
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (s.startsWith("true", i)) {
            i += 4;
            return true;
        } else {
            i += 5;
            return false;
        }
    }

    private Object parseNull() {
        i += 4;
        return null;
    }

    private Number parseNumber() {
        int start = i;

        while (i < s.length() &&
                (Character.isDigit(s.charAt(i)) ||
                 s.charAt(i) == '.' ||
                 s.charAt(i) == '-')) {
            i++;
        }

        String num = s.substring(start, i);

        if (num.contains(".")) return Double.parseDouble(num);
        return Integer.parseInt(num);
    }

    private void skipWhitespace() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    public static void main(String[] args) {
        JsonParserSimple parser = new JsonParserSimple();

        String json = "{\"name\":\"Sagar\",\"age\":28,\"skills\":[\"Java\",\"SystemDesign\"],\"active\":true}";

        Object result = parser.parse(json);

        System.out.println(result);
    }
}