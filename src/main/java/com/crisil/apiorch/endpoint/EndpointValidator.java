package com.crisil.apiorch.endpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.AuthType;
import com.crisil.apiorch.http.HostPolicy;
import com.crisil.apiorch.observability.GlobalExceptionHandler.FieldError;
import com.crisil.apiorch.observability.ValidationFailedException;

/**
 * Collects every field error of an endpoint request in one pass (US-2.1 AC2): Bean Validation, the host policy,
 * the {@code healthPath} rules and the cross-field ranges.
 */
@Component
public class EndpointValidator {

    private final Validator validator;
    private final HostPolicy hostPolicy;

    public EndpointValidator(Validator validator, HostPolicy hostPolicy) {
        this.validator = validator;
        this.hostPolicy = hostPolicy;
    }

    public void validate(EndpointRequest request) {
        List<FieldError> errors = new ArrayList<>();
        for (ConstraintViolation<EndpointRequest> v : validator.validate(request)) {
            errors.add(new FieldError(v.getPropertyPath().toString(), v.getMessage()));
        }
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            hostPolicy.violations(request.baseUrl()).forEach(m -> errors.add(new FieldError("baseUrl", m)));
        }
        String healthPath = request.healthPath();
        if (healthPath != null && (!healthPath.startsWith("/") || healthPath.contains("@") || healthPath.contains("//")
                || healthPath.contains("..") || healthPath.contains("?") || healthPath.contains("#"))) {
            errors.add(new FieldError("healthPath", "must start with / and contain no @, //, .., ? or #"));
        }
        if (request.auth() != null && request.auth().type() != null && request.auth().type() != AuthType.NONE) {
            errors.add(new FieldError("auth.type", "only NONE is supported until downstream auth (US-2.2) is delivered"));
        }
        if (request.resilience() != null) {
            var cb = request.resilience().circuitBreaker();
            if (cb != null && cb.minimumCalls() != null && cb.slidingWindowSize() != null
                    && cb.minimumCalls() > cb.slidingWindowSize()) {
                errors.add(new FieldError("resilience.circuitBreaker.minimumCalls",
                        "must be less than or equal to slidingWindowSize"));
            }
            var retry = request.resilience().retry();
            if (retry != null && retry.initialMs() != null && retry.maxMs() != null && retry.initialMs() > retry.maxMs()) {
                errors.add(new FieldError("resilience.retry.initialMs", "must be less than or equal to maxMs"));
            }
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(FieldError::field));
            throw new ValidationFailedException(errors);
        }
    }
}
