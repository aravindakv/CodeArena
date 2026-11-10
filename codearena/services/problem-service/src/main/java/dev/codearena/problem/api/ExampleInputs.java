package dev.codearena.problem.api;

import dev.codearena.codegen.Signature;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a human-readable example input such as  nums = [3,1,4,6], target = 7
 * into the JSON test-input line  {"nums":[3,1,4,6],"target":7}  used by the RUN mode.
 * Values must already be JSON (numbers, "strings", [arrays], true/false).
 */
public final class ExampleInputs {
    private ExampleInputs() {}

    public static String toJson(String input, Signature sig) {
        List<String> values = splitTopLevel(input);
        if (values.size() != sig.params().size())
            throw new IllegalArgumentException("example input needs " + sig.params().size() + " values: " + input);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            String part = values.get(i);
            int eq = part.indexOf('=');
            String value = (eq >= 0 && part.substring(0, eq).trim().matches("[A-Za-z_][A-Za-z0-9_]*"))
                    ? part.substring(eq + 1).trim() : part.trim();
            if (i > 0) sb.append(',');
            sb.append('"').append(sig.params().get(i).name()).append("\":").append(value);
        }
        return sb.append('}').toString();
    }

    /** Splits on commas that are not inside brackets or quotes. */
    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '[' || c == '{') depth++;
                if (c == ']' || c == '}') depth--;
                if (c == ',' && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }
}