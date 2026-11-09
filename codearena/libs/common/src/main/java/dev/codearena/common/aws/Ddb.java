package dev.codearena.common.aws;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

/**
 * Tiny helpers for the low-level DynamoDB client. The services use plain attribute maps
 * instead of the enhanced client's bean mapping: fewer annotations, nothing hidden.
 */
public final class Ddb {
    private Ddb() {}

    public static AttributeValue s(String v) { return AttributeValue.fromS(v); }

    public static AttributeValue n(long v) { return AttributeValue.fromN(Long.toString(v)); }

    public static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("PK", s(pk), "SK", s(sk));
    }

    public static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item == null ? null : item.get(name);
        return v == null ? null : v.s();
    }

    public static long num(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item == null ? null : item.get(name);
        return v == null || v.n() == null ? 0 : Long.parseLong(v.n());
    }

    public static boolean has(Map<String, AttributeValue> item) {
        return item != null && !item.isEmpty();
    }
}