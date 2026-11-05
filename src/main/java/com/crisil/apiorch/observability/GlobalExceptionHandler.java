package com.crisil.apiorch.observability;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import com.crisil.apiorch.config.PayloadTooLargeException;

/**
 * Maps every API error to RFC 9457 problem+json with a {@code urn:apiorch:problem:<slug>} type and the correlation
 * ID (US-1.1 AC4). Unhandled exceptions become {@code 500 internal-error} with no stack trace or class name.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<Object> handleApiProblem(ApiProblemException ex, WebRequest request) {
        ProblemDetail problem = Problems.of(ex.status(), ex.slug(), ex.getMessage());
        if (ex instanceof ValidationFailedException vf) {
            problem.setProperty("errors", vf.errors());
        }
        ex.properties().forEach(problem::setProperty);
        return respond(problem, new HttpHeaders(), request);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<Object> handlePayloadTooLarge(PayloadTooLargeException ex, WebRequest request) {
        return respond(payloadTooLarge(ex), new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        PayloadTooLargeException tooLarge = findCause(ex, PayloadTooLargeException.class);
        if (tooLarge != null) {
            return respond(payloadTooLarge(tooLarge), new HttpHeaders(), request);
        }
        log.error("Unhandled exception", ex);
        return respond(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
                "An unexpected error occurred."), new HttpHeaders(), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        PayloadTooLargeException tooLarge = findCause(ex, PayloadTooLargeException.class);
        if (tooLarge != null) {
            return respond(payloadTooLarge(tooLarge), headers, request);
        }
        UnrecognizedPropertyException unknown = findCause(ex, UnrecognizedPropertyException.class);
        String detail = unknown != null
                ? "Unknown property '" + unknown.getPropertyName() + "'."
                : "The request body is not valid JSON or does not match the expected shape.";
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED, detail);
        if (unknown != null) {
            problem.setProperty("errors", List.of(new FieldError(unknown.getPropertyName(), "unknown property")));
        }
        return respond(problem, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                "The request has " + ex.getErrorCount() + " invalid field(s).");
        problem.setProperty("errors", errors);
        return respond(problem, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail pd ? pd
                : Problems.of(statusCode, ProblemTypes.forStatus(statusCode), null);
        return respond(problem, headers, request);
    }

    private ResponseEntity<Object> respond(ProblemDetail problem, HttpHeaders headers, WebRequest request) {
        HttpServletRequest servletRequest = request instanceof NativeWebRequest nwr
                ? nwr.getNativeRequest(HttpServletRequest.class) : null;
        Problems.complete(problem, servletRequest);
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return ResponseEntity.status(problem.getStatus()).headers(out).body(problem);
    }

    private static ProblemDetail payloadTooLarge(PayloadTooLargeException ex) {
        return Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, ProblemTypes.PAYLOAD_TOO_LARGE, ex.getMessage());
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    /** One invalid field in a {@code validation-failed} problem. */
    public record FieldError(String field, String message) {
    }
}
