package com.example.fqdownloader.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonParser {
    private final String src;
    private int pos;

    public JsonParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    public JsonValue parse() {
        skipWhitespace();
        return parseValue();
    }

    private JsonValue parseValue() {
        skipWhitespace();
        if (pos >= src.length()) throw new RuntimeException("Unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> new JsonValue.JsonString(parseString());
            case 't', 'f' -> new JsonValue.JsonBoolean(parseBoolean());
            case 'n' -> { parseNull(); yield new JsonValue.JsonNull(); }
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield new JsonValue.JsonNumber(parseNumber());
                }
                throw new RuntimeException("Unexpected character: " + c + " at " + pos);
            }
        };
    }

    private JsonValue.JsonObject parseObject() {
        Map<String, JsonValue> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') { pos++; return new JsonValue.JsonObject(map); }
        while (pos < src.length()) {
            skipWhitespace();
            if (src.charAt(pos) == '"') {
                String key = parseString();
                skipWhitespace(); expect(':'); skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                if (src.charAt(pos) == ',') pos++;
                else if (src.charAt(pos) == '}') { pos++; break; }
                else throw new RuntimeException("Expected ',' or '}' at " + pos);
            } else throw new RuntimeException("Expected '\"' at " + pos);
        }
        return new JsonValue.JsonObject(map);
    }

    private JsonValue.JsonArray parseArray() {
        List<JsonValue> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ']') { pos++; return new JsonValue.JsonArray(list); }
        while (pos < src.length()) {
            list.add(parseValue());
            skipWhitespace();
            if (src.charAt(pos) == ',') pos++;
            else if (src.charAt(pos) == ']') { pos++; break; }
            else throw new RuntimeException("Expected ',' or ']' at " + pos);
        }
        return new JsonValue.JsonArray(list);
    }

    private String parseString() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '"') { pos++; return sb.toString(); }
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) throw new RuntimeException("Unexpected end in escape");
                sb.append(switch (src.charAt(pos)) {
                    case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                    case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    case 'b' -> '\b'; case 'f' -> '\f';
                    case 'u' -> { String h = src.substring(pos + 1, Math.min(pos + 5, src.length())); pos += 4; yield (char) Integer.parseInt(h, 16); }
                    default -> throw new RuntimeException("Invalid escape");
                });
                pos++;
            } else { sb.append(c); pos++; }
        }
        throw new RuntimeException("Unterminated string");
    }

    private double parseNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        if (pos < src.length() && src.charAt(pos) == '.') { pos++; while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++; }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) { pos++; if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++; while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++; }
        return Double.parseDouble(src.substring(start, pos));
    }

    private boolean parseBoolean() {
        if (src.startsWith("true", pos)) { pos += 4; return true; }
        if (src.startsWith("false", pos)) { pos += 5; return false; }
        throw new RuntimeException("Expected boolean at " + pos);
    }

    private void parseNull() { if (src.startsWith("null", pos)) pos += 4; else throw new RuntimeException("Expected null"); }
    private void expect(char c) { if (pos >= src.length() || src.charAt(pos) != c) throw new RuntimeException("Expected '" + c + "'"); pos++; }
    private void skipWhitespace() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }
}
