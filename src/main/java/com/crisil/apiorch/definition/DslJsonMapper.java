package com.crisil.apiorch.definition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The Jackson 2 mapper used only for the DSL (US-3.1). Its own instance, so the shared Boot-managed mapper is never
 * reconfigured. {@code FAIL_ON_UNKNOWN_PROPERTIES} is load-bearing: Boot's mapper disables it (ADR-003 F7), and a
 * silently dropped {@code "dependOn"} typo would make the step depend on the previous one, which is exactly the
 * reorder bug the DSL exists to prevent.
 */
public final class DslJsonMapper {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    private DslJsonMapper() {
    }

    public static JsonMapper get() {
        return MAPPER;
    }
}
