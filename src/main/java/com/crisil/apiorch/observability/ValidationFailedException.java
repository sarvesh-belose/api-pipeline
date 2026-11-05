package com.crisil.apiorch.observability;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.crisil.apiorch.observability.GlobalExceptionHandler.FieldError;

/** {@code 400 validation-failed} listing every field error at once (README §9). */
public class ValidationFailedException extends ApiProblemException {

    private final List<FieldError> errors;

    public ValidationFailedException(List<FieldError> errors) {
        super(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                "The request has " + errors.size() + " invalid field(s).");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
