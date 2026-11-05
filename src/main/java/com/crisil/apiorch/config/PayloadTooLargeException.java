package com.crisil.apiorch.config;

import java.io.IOException;

/**
 * A request body over its path's limit (US-1.1 AC5). An {@link IOException}, so it surfaces from the body stream
 * through any reader; the exception handler finds it in the cause chain and answers {@code 413 payload-too-large}.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limit) {
        super("The request body exceeds the limit of " + limit + " bytes for this path.");
    }
}
