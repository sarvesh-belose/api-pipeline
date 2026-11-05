package com.crisil.apiorch.observability;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Masks everything before it is stored or logged (US-7.3). JSON is masked by key and path on a deep copy; form
 * bodies by key; URL query parameters by key, with the callback token {@code t} always masked; headers by name.
 * Anything that can't be parsed and masked is stored only as {@code <unparseable body: N bytes, content-type>},
 * never as a raw prefix. The masked value is {@code ***}.
 */
public final class PayloadMasker {

    public static final String MASK = "***";
    static final String CALLBACK_TOKEN = "t";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private PayloadMasker() {
    }

    /** A masked deep copy; {@code node} is never changed. */
    public static JsonNode maskJson(JsonNode node, MaskingPolicy policy) {
        JsonNode copy = node.deepCopy();
        walk(copy, new ArrayList<>(), policy);
        return copy;
    }

    private static void walk(JsonNode node, List<String> path, MaskingPolicy policy) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> masked = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                path.add(field.getKey().toLowerCase(Locale.ROOT));
                if (policy.key(field.getKey()) || policy.path(path)) {
                    masked.add(field.getKey());
                } else {
                    walk(field.getValue(), path, policy);
                }
                path.remove(path.size() - 1);
            }
            masked.forEach(name -> object.put(name, MASK));
        } else if (node instanceof ArrayNode array) {
            path.add(MaskingPolicy.ANY_ELEMENT);
            for (int i = 0; i < array.size(); i++) {
                if (policy.path(path) && array.get(i).isValueNode()) {
                    array.set(i, array.textNode(MASK));
                } else {
                    walk(array.get(i), path, policy);
                }
            }
            path.remove(path.size() - 1);
        }
    }

    public static Map<String, String> maskHeaders(Map<String, String> headers, MaskingPolicy policy) {
        if (headers == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((name, value) -> out.put(name, policy.header(name) ? MASK : value));
        return out;
    }

    /** The URL with masked query values; path and order are unchanged. */
    public static String maskUrl(String url, MaskingPolicy policy) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        int hash = url.indexOf('#', q);
        String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
        return url.substring(0, q + 1) + maskPairs(query, policy, true) + (hash < 0 ? "" : url.substring(hash));
    }

    public static String maskForm(String body, MaskingPolicy policy) {
        return body == null ? null : maskPairs(body, policy, false);
    }

    private static String maskPairs(String text, MaskingPolicy policy, boolean url) {
        StringBuilder out = new StringBuilder();
        for (String pair : text.split("&", -1)) {
            if (out.length() > 0) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            boolean mask = eq >= 0 && (policy.key(decoded) || (url && decoded.equals(CALLBACK_TOKEN)));
            out.append(mask ? name + "=" + MASK : pair);
        }
        return out.toString();
    }

    /**
     * A body ready to store: masked JSON or form text, else the placeholder. {@code bytes} is the true size, which
     * the placeholder reports.
     */
    public static String maskBody(String body, String contentType, long bytes, MaskingPolicy policy) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("application/json") || type.endsWith("+json")) {
            try {
                return JSON.writeValueAsString(maskJson(JSON.readTree(body), policy));
            } catch (JsonProcessingException e) {
                return placeholder(bytes, contentType);
            }
        }
        if (type.equals("application/x-www-form-urlencoded")) {
            return maskForm(body, policy);
        }
        return placeholder(bytes, contentType);
    }

    public static String placeholder(long bytes, String contentType) {
        return "<unparseable body: " + bytes + " bytes, " + (contentType == null ? "no content-type" : contentType)
                + ">";
    }
}
