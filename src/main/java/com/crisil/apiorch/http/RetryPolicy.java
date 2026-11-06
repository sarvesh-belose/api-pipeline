package com.crisil.apiorch.http;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.random.RandomGenerator;

import com.crisil.apiorch.definition.PipelineDefinition.RetrySpec;
import com.crisil.apiorch.endpoint.EndpointConfig;

/**
 * The effective retry policy of one step (US-5.2): each field from the step's {@code retry}, else the pipeline's
 * {@code defaults.retry}, else the endpoint's (which already holds the global defaults). Delays are computed here and
 * never wait: the caller schedules the next attempt.
 */
public record RetryPolicy(int maxAttempts, Backoff backoff, int initialMs, int maxMs, List<String> retryOn) {

    public enum Backoff { FIXED, EXPONENTIAL, EXPONENTIAL_JITTER }

    public static RetryPolicy resolve(RetrySpec step, RetrySpec defaults, EndpointConfig.Retry endpoint) {
        Integer maxAttempts = first(step != null ? step.maxAttempts() : null,
                defaults != null ? defaults.maxAttempts() : null);
        Integer initialMs = first(step != null ? step.initialMs() : null, defaults != null ? defaults.initialMs() : null);
        Integer maxMs = first(step != null ? step.maxMs() : null, defaults != null ? defaults.maxMs() : null);
        Backoff backoff = step != null && step.backoff() != null ? Backoff.valueOf(step.backoff().name())
                : defaults != null && defaults.backoff() != null ? Backoff.valueOf(defaults.backoff().name())
                : endpoint != null ? Backoff.valueOf(endpoint.backoff().name()) : Backoff.EXPONENTIAL_JITTER;
        List<String> retryOn = step != null && step.retryOn() != null ? step.retryOn()
                : defaults != null ? defaults.retryOn() : null;
        return new RetryPolicy(
                maxAttempts != null ? maxAttempts : endpoint != null ? endpoint.maxAttempts() : 1,
                backoff,
                initialMs != null ? initialMs : endpoint != null ? endpoint.initialMs() : 200,
                maxMs != null ? maxMs : endpoint != null ? endpoint.maxMs() : 2000,
                retryOn == null ? RetryClassifier.DEFAULT_RETRY_ON : retryOn);
    }

    private static Integer first(Integer a, Integer b) {
        return a != null ? a : b;
    }

    /**
     * The delay before attempt {@code failedAttempt + 1}: {@code Retry-After} when given (capped at {@code maxMs}),
     * else the backoff; full jitter is uniform in {@code [0, min(maxMs, initialMs·2^(n-1))]} (AC4, AC5).
     */
    public long delayMs(int failedAttempt, Long retryAfterMs, RandomGenerator random) {
        if (retryAfterMs != null) {
            return Math.min(Math.max(0, retryAfterMs), maxMs);
        }
        long ceiling = Math.min((long) maxMs, (long) initialMs << Math.min(30, Math.max(0, failedAttempt - 1)));
        return switch (backoff) {
            case FIXED -> Math.min(initialMs, maxMs);
            case EXPONENTIAL -> ceiling;
            case EXPONENTIAL_JITTER -> random.nextLong(ceiling + 1);
        };
    }

    /** {@code Retry-After} as delta-seconds or an HTTP-date; {@code null} when absent or unreadable. */
    public static Long retryAfterMs(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return Long.parseLong(v) * 1000;
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Duration.between(now, at).toMillis());
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
