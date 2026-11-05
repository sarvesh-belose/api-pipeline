package com.crisil.apiorch.http.auth;

import java.io.IOException;

/**
 * Auth could not be applied, so no request was sent to the downstream API, or the API rejected a fresh token
 * (US-2.2 AC4, AC5). The attempt fails with outcome {@code AUTH_ERROR} and this {@link #code()}; step retry does
 * not retry it. An {@link IOException} so it passes through the HTTP client's interceptor chain.
 */
public class AuthFailureException extends IOException {

    public static final String SECRET_UNAVAILABLE = "SECRET_UNAVAILABLE";
    public static final String TOKEN_FETCH_FAILED = "TOKEN_FETCH_FAILED";
    public static final String TOKEN_REJECTED = "TOKEN_REJECTED";

    private final String code;

    public AuthFailureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
