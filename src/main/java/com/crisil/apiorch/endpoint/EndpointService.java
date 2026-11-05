package com.crisil.apiorch.endpoint;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.ValidationFailedException;
import com.crisil.apiorch.observability.GlobalExceptionHandler.FieldError;

/** Endpoint CRUD (US-2.1). Every change publishes {@link EndpointChangedEvent} so runtimes rebuild after commit. */
@Service
public class EndpointService {

    private static final Logger log = LoggerFactory.getLogger(EndpointService.class);

    private final EndpointRepository repository;
    private final EndpointMapper mapper;
    private final EndpointValidator validator;
    private final EndpointReferenceChecker references;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EndpointService(EndpointRepository repository, EndpointMapper mapper, EndpointValidator validator,
            EndpointReferenceChecker references, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.validator = validator;
        this.references = references;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public EndpointConfig create(EndpointRequest request, String actor) {
        validator.validate(request);
        if (repository.existsByName(request.name())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "already-exists",
                    "Endpoint '" + request.name() + "' already exists.");
        }
        EndpointEntity entity = new EndpointEntity(UUID.randomUUID(), request.name(), Instant.now(clock), actor);
        mapper.apply(request, entity);
        repository.saveAndFlush(entity);
        events.publishEvent(new EndpointChangedEvent(entity.getName()));
        log.info("endpoint created name={} version={} actor={}", entity.getName(), entity.getRowVersion(), actor);
        return mapper.toConfig(entity);
    }

    @Transactional
    public EndpointConfig update(String name, EndpointRequest request, Long ifMatch, String actor) {
        if (!name.equals(request.name())) {
            throw new ValidationFailedException(List.of(new FieldError("name", "must equal the path name '" + name + "'")));
        }
        validator.validate(request);
        EndpointEntity entity = find(name);
        if (ifMatch == null) {
            throw new ApiProblemException(HttpStatus.PRECONDITION_REQUIRED, "precondition-required",
                    "Send If-Match with the endpoint's current version.");
        }
        if (ifMatch != entity.getRowVersion()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "version-conflict",
                    "Endpoint '" + name + "' is at version " + entity.getRowVersion() + ", not " + ifMatch + ".")
                    .with("currentVersion", entity.getRowVersion());
        }
        EndpointConfig before = mapper.toConfig(entity);
        requireReferencesOnHostChange(before, request);
        mapper.apply(request, entity);
        entity.touch(Instant.now(clock), actor);
        repository.saveAndFlush(entity);
        EndpointConfig after = mapper.toConfig(entity);
        events.publishEvent(new EndpointChangedEvent(name));
        log.info("endpoint updated name={} version={} actor={} changed={}", name, after.version(), actor,
                changedFields(before, after));
        return after;
    }

    /**
     * US-2.2 AC1b: moving an endpoint (or its token URL) to another host must re-supply the secret references in the
     * same request, so credentials are never silently redirected to a new host.
     */
    static void requireReferencesOnHostChange(EndpointConfig before, EndpointRequest request) {
        if (before.authType() == EndpointRequest.AuthType.NONE) {
            return;
        }
        boolean hostChanged = !host(before.baseUrl()).equalsIgnoreCase(host(request.baseUrl()));
        EndpointRequest.Auth after = request.auth();
        boolean tokenUrlChanged = after != null && before.auth().tokenUrl() != null
                && !before.auth().tokenUrl().equals(after.tokenUrl());
        if ((hostChanged || tokenUrlChanged) && (after == null || after.type() != before.authType())) {
            throw new ValidationFailedException(List.of(new FieldError("auth",
                    "re-enter secret references when the host changes")));
        }
    }

    private static String host(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null ? host : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @Transactional
    public void delete(String name, String actor) {
        EndpointEntity entity = find(name);
        List<EndpointReferenceChecker.Reference> inUse = references.referencesTo(name);
        if (!inUse.isEmpty()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "endpoint-in-use",
                    "Endpoint '" + name + "' is referenced " + inUse.size() + " time(s).")
                    .with("references", inUse);
        }
        repository.delete(entity);
        repository.flush();
        events.publishEvent(new EndpointChangedEvent(name));
        log.info("endpoint deleted name={} actor={}", name, actor);
    }

    @Transactional(readOnly = true)
    public EndpointConfig get(String name) {
        return mapper.toConfig(find(name));
    }

    @Transactional(readOnly = true)
    public List<EndpointConfig> list() {
        return repository.findAllByOrderByNameAsc().stream().map(mapper::toConfig).toList();
    }

    private EndpointEntity find(String name) {
        return repository.findByName(name).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "endpoint-not-found", "Endpoint '" + name + "' does not exist."));
    }

    static List<String> changedFields(EndpointConfig a, EndpointConfig b) {
        List<String> changed = new ArrayList<>();
        diff(changed, "description", a.description(), b.description());
        diff(changed, "baseUrl", a.baseUrl(), b.baseUrl());
        diff(changed, "healthPath", a.healthPath(), b.healthPath());
        diff(changed, "connectTimeoutMs", a.connectTimeoutMs(), b.connectTimeoutMs());
        diff(changed, "readTimeoutMs", a.readTimeoutMs(), b.readTimeoutMs());
        diff(changed, "httpVersion", a.httpVersion(), b.httpVersion());
        diff(changed, "enabled", a.enabled(), b.enabled());
        diff(changed, "auth", a.auth(), b.auth());
        diff(changed, "resilience.circuitBreaker", a.circuitBreaker(), b.circuitBreaker());
        diff(changed, "resilience.bulkhead", a.bulkhead(), b.bulkhead());
        diff(changed, "resilience.retry", a.retry(), b.retry());
        return changed;
    }

    private static void diff(List<String> out, String field, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            out.add(field);
        }
    }
}
