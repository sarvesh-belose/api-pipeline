package com.crisil.apiorch.observability;

import java.net.URI;

import org.springframework.http.HttpStatusCode;

/** Problem type slugs from README §9. Every problem+json response carries {@code urn:apiorch:problem:<slug>}. */
public final class ProblemTypes {

    public static final String PREFIX = "urn:apiorch:problem:";

    public static final String VALIDATION_FAILED = "validation-failed";
    public static final String NOT_FOUND = "not-found";
    public static final String METHOD_NOT_ALLOWED = "method-not-allowed";
    public static final String NOT_ACCEPTABLE = "not-acceptable";
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported-media-type";
    public static final String PAYLOAD_TOO_LARGE = "payload-too-large";
    public static final String SHUTTING_DOWN = "shutting-down";
    public static final String INTERNAL_ERROR = "internal-error";

    /** Request attribute and problem property holding the correlation ID (US-1.3). */
    public static final String CORRELATION_ID = "correlationId";

    private ProblemTypes() {
    }

    public static URI type(String slug) {
        return URI.create(PREFIX + slug);
    }

    /** The slug for a framework-raised status that has no more specific slug. */
    public static String forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> VALIDATION_FAILED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 503 -> SHUTTING_DOWN;
            default -> status.is4xxClientError() ? VALIDATION_FAILED : INTERNAL_ERROR;
        };
    }
}
