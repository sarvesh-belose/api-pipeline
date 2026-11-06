package com.crisil.apiorch.config;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.endpoint.EndpointService;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.endpoint.dto.EndpointRequest;
import com.crisil.apiorch.pipeline.DraftService;
import com.crisil.apiorch.pipeline.PublishService;
import com.crisil.apiorch.pipeline.dto.DraftRequest;

/**
 * The local demo seed (US-8.1 slice A), only with {@code apiorch.seed.enabled=true} (the {@code local} profile). It
 * upserts the demo endpoints by name and publishes {@code DEMO_ONBOARD} v1 through the normal services, so the seed
 * is validated like any admin change, and only when no version exists yet: a restart neither duplicates nor fails.
 * {@code apiorch.seed.base-url} points every endpoint elsewhere (tests start WireMock on a random port).
 */
@Component
@ConditionalOnProperty(name = "apiorch.seed.enabled", havingValue = "true")
public class LocalSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedRunner.class);

    static final String ACTOR = "seed";
    static final String PIPELINE = "DEMO_ONBOARD";

    private final EndpointService endpoints;
    private final DraftService drafts;
    private final PublishService publisher;
    private final ObjectMapper json;
    private final String baseUrl;

    public LocalSeedRunner(EndpointService endpoints, DraftService drafts, PublishService publisher, ObjectMapper json,
            @Value("${apiorch.seed.base-url:}") String baseUrl) {
        this.endpoints = endpoints;
        this.drafts = drafts;
        this.publisher = publisher;
        this.json = json;
        this.baseUrl = baseUrl;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        int created = 0;
        int updated = 0;
        for (JsonNode node : read("seed/endpoints-local.json")) {
            ObjectNode endpoint = (ObjectNode) node;
            if (!baseUrl.isBlank()) {
                endpoint.put("baseUrl", baseUrl);
            }
            EndpointRequest request = json.treeToValue(endpoint, EndpointRequest.class);
            if (endpoints.list().stream().anyMatch(e -> e.name().equals(request.name()))) {
                endpoints.update(request.name(), request, endpoints.get(request.name()).version(), ACTOR);
                updated++;
            } else {
                endpoints.create(request, ACTOR);
                created++;
            }
        }
        boolean exists = true;
        boolean published;
        try {
            published = !publisher.list(PIPELINE).isEmpty();
        } catch (ApiProblemException e) {
            if (e.status() != org.springframework.http.HttpStatus.NOT_FOUND) {
                throw e;
            }
            exists = false;
            published = false;
        }
        if (!published) {
            JsonNode v1 = read("seed/DEMO_ONBOARD.json");
            if (exists) {
                // an earlier start saved the draft but stopped before publishing
                drafts.put(PIPELINE, new DraftRequest(null, null, v1), null, ACTOR);
            } else {
                drafts.create(new DraftRequest(PIPELINE, null, v1), ACTOR);
            }
            publisher.publish(PIPELINE, "seeded v1 (US-8.1)", ACTOR);
        }
        log.info("local seed done endpointsCreated={} endpointsUpdated={} pipelinePublished={}", created, updated,
                !published);
    }

    private JsonNode read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return json.readTree(in);
        }
    }
}
