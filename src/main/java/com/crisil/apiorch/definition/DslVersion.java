package com.crisil.apiorch.definition;

/**
 * The DSL revision this build reads and writes (US-3.1 AC8, README §11 {@code apiorch.definition.dsl-version}). A
 * build constant, not configuration: the compiler keeps a handler per revision it has ever supported, and
 * dropping one is a breaking change that needs the stored definitions migrated first.
 */
public final class DslVersion {

    public static final int CURRENT = 1;

    private DslVersion() {
    }
}
