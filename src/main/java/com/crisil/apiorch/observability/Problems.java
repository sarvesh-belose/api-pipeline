package com.crisil.apiorch.observability;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds and completes problem details so every one has the README §9 shape (US-1.1 AC4). */
public final class Problems {

    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String slug, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ProblemTypes.type(slug));
        return problem;
    }

    /**
     * Fills in what a framework-built problem lacks: the slug type, a title, the request path and the correlation
     * ID. Values already set are kept.
     */
    public static ProblemDetail complete(ProblemDetail problem, HttpServletRequest request) {
        HttpStatusCode status = HttpStatusCode.valueOf(problem.getStatus());
        if (problem.getType() == null || ABOUT_BLANK.equals(problem.getType())) {
            problem.setType(ProblemTypes.type(ProblemTypes.forStatus(status)));
        }
        if (problem.getTitle() == null) {
            HttpStatus known = HttpStatus.resolve(status.value());
            problem.setTitle(known != null ? known.getReasonPhrase() : "Error");
        }
        if (problem.getInstance() == null && request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        Object correlationId = request != null ? request.getAttribute(CorrelationIdFilter.ATTRIBUTE) : null;
        if (correlationId == null) {
            correlationId = MDC.get(ProblemTypes.CORRELATION_ID);
        }
        if (correlationId != null) {
            problem.setProperty(ProblemTypes.CORRELATION_ID, correlationId);
        }
        return problem;
    }
}
