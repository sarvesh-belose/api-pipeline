package testsupport.engine;

import java.util.UUID;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.DslJsonMapper;

import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** Pipelines whose steps call a real (WireMock) endpoint, for the HTTP step executor tests (US-4.3, US-7.2). */
public final class HttpPipelines {

    private HttpPipelines() {
    }

    /** Registers an endpoint with a unique name at {@code baseUrl} and returns the name. */
    public static String endpoint(Http http, String baseUrl) throws Exception {
        String name = "wm-" + UUID.randomUUID().toString().substring(0, 8);
        var res = http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name, baseUrl));
        if (res.statusCode() != 201) {
            throw new IllegalStateException("endpoint create failed " + res.statusCode() + ": " + res.body());
        }
        return name;
    }

    /** A definition with no steps yet; add them with {@link #step}. */
    public static ObjectNode pipeline(String code) {
        ObjectNode doc = DslJsonMapper.get().createObjectNode();
        doc.put("dslVersion", 1);
        doc.put("code", code);
        doc.putArray("steps");
        return doc;
    }

    /** Appends a step and returns it for further fields. */
    public static ObjectNode step(ObjectNode pipeline, String id, String endpoint, String method, String path) {
        ArrayNode steps = pipeline.withArray("steps");
        ObjectNode step = steps.addObject();
        step.put("id", id);
        step.put("endpoint", endpoint);
        step.put("method", method);
        step.put("path", path);
        return step;
    }
}
