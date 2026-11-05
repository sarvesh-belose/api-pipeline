package testsupport.web;

import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.DslJsonMapper;

/** Draft and publish calls over the admin API (US-3.1, US-3.3). */
public final class Drafts {

    private Drafts() {
    }

    /** Creates the pipeline with {@code definition} as its draft, or replaces the draft if it exists. */
    public static void save(Http http, ObjectNode definition) throws Exception {
        String code = definition.path("code").asText();
        ObjectNode body = DslJsonMapper.get().createObjectNode();
        body.set("definition", definition);
        HttpResponse<String> current = http.get("/api/v1/admin/pipelines/" + code + "/draft");
        HttpResponse<String> res;
        if (current.statusCode() == 200) {
            res = http.putJson("/api/v1/admin/pipelines/" + code + "/draft", body.toString(),
                    current.headers().firstValue("ETag").orElseThrow());
        } else if (http.get("/api/v1/admin/pipelines/" + code + "/versions").statusCode() == 200) {
            res = http.putJson("/api/v1/admin/pipelines/" + code + "/draft", body.toString(), null);
        } else {
            body.put("code", code);
            res = http.postJson("/api/v1/admin/pipelines", body.toString());
        }
        if (res.statusCode() >= 300) {
            throw new IllegalStateException("draft save failed " + res.statusCode() + ": " + res.body());
        }
    }

    public static HttpResponse<String> publish(Http http, String code, String comment) throws Exception {
        String body = comment == null ? "{}" : DslJsonMapper.get().createObjectNode().put("comment", comment).toString();
        return http.postJson("/api/v1/admin/pipelines/" + code + "/publish", body);
    }

    /** Saves and publishes, failing unless the publish returns 201; returns the new version. */
    public static int savePublished(Http http, ObjectNode definition) throws Exception {
        save(http, definition);
        HttpResponse<String> res = publish(http, definition.path("code").asText(), null);
        if (res.statusCode() != 201) {
            throw new IllegalStateException("publish failed " + res.statusCode() + ": " + res.body());
        }
        JsonNode body = DslJsonMapper.get().readTree(res.body());
        return body.path("version").asInt();
    }
}
