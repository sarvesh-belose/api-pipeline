package com.crisil.apiorch.web;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.ProblemTypes;

/** Row versions as strong ETags and back, for {@code If-Match} on admin resources (US-2.1 AC3, US-3.1). */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /** The version in an {@code If-Match} value such as {@code "3"} or {@code W/"3"}; {@code null} when absent. */
    public static Long parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "If-Match must be a version ETag such as \"3\".")
                    .with("errors", List.of(new com.crisil.apiorch.observability.GlobalExceptionHandler.FieldError(
                            "If-Match", "is not a version ETag")));
        }
    }
}
