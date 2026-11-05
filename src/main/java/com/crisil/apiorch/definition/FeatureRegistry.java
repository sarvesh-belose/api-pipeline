package com.crisil.apiorch.definition;

/**
 * Engine features built into this release (US-3.2 AC7). Code constants, not configuration, so production cannot
 * switch on a half-built feature; each is flipped by the story that delivers it. A definition that uses a disabled
 * feature fails validation and publishing with {@code FEATURE_NOT_AVAILABLE}.
 */
public final class FeatureRegistry {

    /** {@code mode: ASYNC_CALLBACK}, {@code callback}, {@code blocksRunCompletion} (US-6.1). */
    public static final boolean ASYNC_STEPS = false;

    /** {@code when} (US-4.5). */
    public static final boolean CONDITIONS = false;

    /** {@code compensate} (US-5.6). */
    public static final boolean COMPENSATION = false;

    private FeatureRegistry() {
    }
}
