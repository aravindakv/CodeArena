package dev.codearena.judge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Output comparison modes (set per problem in signature.compare):
 *   EXACT            the JSON text must match exactly (after trimming)
 *   JSON             parsed values must be equal; 2 and 2.0 count as equal
 *   UNORDERED_ARRAY  arrays with the same elements in any order (one level deep)
 *   FLOAT_1E6        numbers (or arrays of numbers) within 1e-6 absolute or relative error
 */
final class Comparators {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Comparators() {}

    static boolean equal(String mode, String expected, String actual) {
        if (actual == null) return false;
        String e = expected.trim(), a = actual.trim();
        try {
            return switch (mode == null ? "JSON" : mode) {
                case "EXACT" -> e.equals(a);
                case "UNORDERED_ARRAY" -> unordered(JSON.readTree(e), JSON.readTree(a));
                case "FLOAT_1E6" -> close(JSON.readTree(e), JSON.readTree(a));
                default -> same(JSON.readTree(e), JSON.readTree(a));
            };
        } catch (Exception ex) {
            return false;   // the program printed something that is not JSON
        }
    }

    static boolean same(JsonNode e, JsonNode a) {
        if (e.isNumber() && a.isNumber()) return e.decimalValue().compareTo(a.decimalValue()) == 0;
        if (e.isArray() && a.isArray()) {
            if (e.size() != a.size()) return false;
            for (int i = 0; i < e.size(); i++) if (!same(e.get(i), a.get(i))) return false;
            return true;
        }
        if (e.isObject() && a.isObject()) {
            if (e.size() != a.size()) return false;
            for (Map.Entry<String, JsonNode> f : e.properties()) {
                if (!a.has(f.getKey()) || !same(f.getValue(), a.get(f.getKey()))) return false;
            }
            return true;
        }
        return e.equals(a);
    }

    static boolean unordered(JsonNode e, JsonNode a) {
        if (!e.isArray() || !a.isArray() || e.size() != a.size()) return same(e, a);
        return same(sorted(e), sorted(a));
    }

    private static JsonNode sorted(JsonNode arr) {
        List<JsonNode> items = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) items.add(arr.get(i));
        items.sort(Comparator.comparing(JsonNode::toString));
        return JSON.valueToTree(items);
    }

    static boolean close(JsonNode e, JsonNode a) {
        if (e.isNumber() && a.isNumber()) {
            double x = e.doubleValue(), y = a.doubleValue();
            double diff = Math.abs(x - y);
            return diff <= 1e-6 || diff <= 1e-6 * Math.max(Math.abs(x), Math.abs(y));
        }
        if (e.isArray() && a.isArray()) {
            if (e.size() != a.size()) return false;
            for (int i = 0; i < e.size(); i++) if (!close(e.get(i), a.get(i))) return false;
            return true;
        }
        return same(e, a);
    }
}