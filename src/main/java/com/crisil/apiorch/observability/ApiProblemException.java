package com.crisil.apiorch.observability;

import org.springframework.http.HttpStatus;

/** A business error that maps one-to-one to a problem+json response (README §9). */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;

    public ApiProblemException(HttpStatus status, String slug, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }
}
