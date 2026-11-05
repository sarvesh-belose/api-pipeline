package com.crisil.apiorch.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.DslJsonMapper;

/**
 * The canonical form a published checksum covers (US-3.3): object keys sorted, array order preserved, so a reorder
 * of {@code steps[]} is a change and a reordering of keys is not.
 */
final class Canonical {

    private Canonical() {
    }

    static String of(JsonNode document) {
        try {
            return DslJsonMapper.get().writeValueAsString(sorted(document));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise the definition", e);
        }
    }

    static String checksum(JsonNode document) {
        return DraftService.sha256(of(document));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode out = DslJsonMapper.get().createObjectNode();
            for (String name : names) {
                out.set(name, sorted(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = DslJsonMapper.get().createArrayNode();
            node.forEach(item -> out.add(sorted(item)));
            return out;
        }
        return node;
    }
}
