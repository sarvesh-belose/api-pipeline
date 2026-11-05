package com.crisil.apiorch.endpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.AuthType;
import com.crisil.apiorch.http.HostPolicy;
import com.crisil.apiorch.http.auth.SecretReferencePolicy;
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
    private final SecretReferencePolicy secretPolicy;

    public EndpointValidator(Validator validator, HostPolicy hostPolicy, SecretReferencePolicy secretPolicy) {
        this.validator = validator;
        this.hostPolicy = hostPolicy;
        this.secretPolicy = secretPolicy;
    }

    /**
     * US-2.2: each type has exactly its own fields; secrets are confined references; a literal in {@code secret},
     * {@code password} or {@code clientSecret} is refused (AC2); the token URL obeys the host policy (AC1b).
     */
    private void validateAuth(EndpointRequest.Auth a, List<FieldError> errors) {
        for (String[] literal : new String[][] {{"secret", a.secret()}, {"password", a.password()},
                {"clientSecret", a.clientSecret()}}) {
            if (literal[1] != null) {
                errors.add(new FieldError("auth." + literal[0], "secrets must be references; use the "
                        + literal[0] + "Ref field with env:NAME or file:/path"));
            }
        }
        Set<String> required = switch (a.type()) {
            case NONE -> Set.of();
            case API_KEY -> Set.of("headerName", "secretRef");
            case BEARER -> Set.of("secretRef");
            case BASIC -> Set.of("username", "passwordRef");
            case OAUTH2_CLIENT_CREDENTIALS -> Set.of("tokenUrl", "clientId", "clientSecretRef");
        };
        Set<String> optional = a.type() == AuthType.OAUTH2_CLIENT_CREDENTIALS ? Set.of("scope", "audience") : Set.of();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("headerName", a.headerName());
        fields.put("secretRef", a.secretRef());
        fields.put("username", a.username());
        fields.put("passwordRef", a.passwordRef());
        fields.put("tokenUrl", a.tokenUrl());
        fields.put("clientId", a.clientId());
        fields.put("clientSecretRef", a.clientSecretRef());
        fields.put("scope", a.scope());
        fields.put("audience", a.audience());
        fields.forEach((name, value) -> {
            boolean present = value != null && !value.isBlank();
            if (required.contains(name) && !present) {
                errors.add(new FieldError("auth." + name, "is required for " + a.type()));
            } else if (!required.contains(name) && !optional.contains(name) && value != null) {
                errors.add(new FieldError("auth." + name, "is not used by " + a.type()));
            } else if (present && name.endsWith("Ref")) {
                secretPolicy.violations(value).forEach(m -> errors.add(new FieldError("auth." + name, m)));
            } else if (present && name.equals("tokenUrl")) {
                hostPolicy.violations(value).forEach(m -> errors.add(new FieldError("auth.tokenUrl", m)));
            }
        });
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
        if (request.auth() != null && request.auth().type() != null) {
            validateAuth(request.auth(), errors);
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
