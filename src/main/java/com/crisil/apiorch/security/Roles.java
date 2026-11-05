package com.crisil.apiorch.security;

/** The three apiorch roles, as JWT claim values (README §12). */
public final class Roles {

    public static final String RUN = "apiorch.run";
    public static final String ADMIN = "apiorch.admin";
    public static final String OPS = "apiorch.ops";

    private Roles() {
    }
}
