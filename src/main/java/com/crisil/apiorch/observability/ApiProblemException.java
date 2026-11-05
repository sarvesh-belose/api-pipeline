package com.crisil.apiorch.observability;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/** A business error that maps one-to-one to a problem+json response (README §9). */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final Map<String, Object> properties = new LinkedHashMap<>();

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

    /** Adds an extension member to the problem body, for example the references of {@code endpoint-in-use}. */
    public ApiProblemException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
