package com.crisil.apiorch.step;

import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Turns a downstream response into {@code steps.<id>.headers} and {@code steps.<id>.body} (US-4.3 AC7). JSON media
 * types ({@code application/json}, {@code +json}) are parsed, keeping integers above 2^53 exact (ADR-002 D7);
 * anything else, or JSON that doesn't parse, is a UTF-8 string; an empty body is {@code null}. Header names are put
 * in their usual capitalisation ({@code Location}, {@code X-Ref}), because the JDK client lowercases them and
 * mappings are written as {@code steps.api1.headers.Location}.
 */
public final class ResponseParser {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ResponseParser() {
    }

    public static JsonNode body(String contentType, String text) {
        if (text == null || text.isEmpty()) {
            return NullNode.getInstance();
        }
        if (isJson(contentType)) {
            try {
                return JSON.readTree(text);
            } catch (JsonProcessingException e) {
                return JsonNodeFactory.instance.textNode(text);
            }
        }
        return JsonNodeFactory.instance.textNode(text);
    }

    public static ObjectNode headers(Map<String, String> headers) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        headers.forEach((name, value) -> out.put(canonical(name), value));
        return out;
    }

    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.equals("application/json") || type.endsWith("+json");
    }

    /** {@code x-request-id} becomes {@code X-Request-Id}. */
    static String canonical(String name) {
        StringBuilder out = new StringBuilder(name.length());
        boolean upper = true;
        for (char c : name.toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = c == '-';
        }
        return out.toString();
    }
}
