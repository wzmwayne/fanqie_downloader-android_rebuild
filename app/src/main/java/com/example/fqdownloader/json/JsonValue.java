package com.example.fqdownloader.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface JsonValue {
    record JsonObject(Map<String, JsonValue> map) implements JsonValue {
        public JsonValue get(String key) { return map.get(key); }
        public String str(String key) {
            var v = map.get(key);
            return v instanceof JsonString s ? s.value() : null;
        }
        public String str(String key, String def) {
            var s = str(key);
            return s != null ? s : def;
        }
        public int integer(String key, int def) {
            var v = map.get(key);
            return v instanceof JsonNumber n ? n.intValue() : def;
        }
        public long longv(String key, long def) {
            var v = map.get(key);
            return v instanceof JsonNumber n ? n.longValue() : def;
        }
        public List<JsonValue> arr(String key) {
            var v = map.get(key);
            return v instanceof JsonArray a ? a.list() : Collections.emptyList();
        }
    }
    record JsonArray(List<JsonValue> list) implements JsonValue {}
    record JsonString(String value) implements JsonValue {}
    record JsonNumber(double value) implements JsonValue {
        public long longValue() { return (long) value; }
        public int intValue() { return (int) value; }
    }
    record JsonBoolean(boolean value) implements JsonValue {}
    record JsonNull() implements JsonValue {}

    static String optString(JsonValue val, String key) {
        if (val instanceof JsonObject obj) {
            var s = obj.str(key);
            return s != null ? s : "";
        }
        return "";
    }

    static JsonArray optArray(JsonValue val, String key) {
        if (val instanceof JsonObject obj) {
            return new JsonArray(new ArrayList<>(obj.arr(key)));
        }
        return new JsonArray(new ArrayList<>());
    }
}
