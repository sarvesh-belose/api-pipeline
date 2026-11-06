package com.crisil.apiorch.http;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether a failed attempt may be retried (US-5.2). Failures before sending ({@code CONNECT_ERROR},
 * {@code BULKHEAD_FULL}) are always safe; a sent request is retried only when the step is idempotent. 408 and 500
 * are retried only when listed in {@code retryOn}; {@code DEADLINE_EXCEEDED}, other 4xx and every check, auth,
 * expression, size and circuit failure never are.
 */
public final class RetryClassifier {

    /** The {@code retryOn} default. */
    public static final List<String> DEFAULT_RETRY_ON = List.of("CONNECT_ERROR", "IO_ERROR", "TIMEOUT", "429", "502",
            "503", "504");

    private RetryClassifier() {
    }

    /**
     * The retry reason ({@code CONNECT_ERROR}, {@code HTTP_503}, …), or {@code null} when the failure is permanent.
     *
     * @param outcome    the attempt outcome
     * @param httpStatus the response status, for {@code HTTP_ERROR}
     * @param idempotent the step's effective {@code idempotent}
     * @param retryOn    the effective tokens; {@code null} means {@link #DEFAULT_RETRY_ON}
     */
    public static String reason(String outcome, Integer httpStatus, boolean idempotent, List<String> retryOn) {
        Set<String> tokens = (retryOn == null ? DEFAULT_RETRY_ON : retryOn).stream()
                .map(t -> t.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        if (outcome == null) {
            return null;
        }
        switch (outcome) {
            case "BULKHEAD_FULL":
                return outcome;
            case "CONNECT_ERROR":
                return tokens.contains(outcome) ? outcome : null;
            case "TIMEOUT":
            case "IO_ERROR":
                return idempotent && tokens.contains(outcome) ? outcome : null;
            case "HTTP_ERROR":
                if (!idempotent || httpStatus == null) {
                    return null;
                }
                boolean listed = tokens.contains(String.valueOf(httpStatus))
                        || (httpStatus >= 500 && httpStatus <= 599 && tokens.contains("5XX"));
                return listed && retryableStatus(httpStatus) ? "HTTP_" + httpStatus : null;
            default:
                return null;
        }
    }

    /** Statuses that can be retried at all; 408 and 500 only when listed, which {@link #reason} checks. */
    private static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500 && status <= 599;
    }
}
