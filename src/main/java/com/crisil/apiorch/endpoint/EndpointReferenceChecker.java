package com.crisil.apiorch.endpoint;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Finds every place an endpoint is still used (US-2.1 AC5): each pipeline's draft, its latest published version,
 * and every version used by a run that has not ended. Steps reference endpoints in {@code endpoint} and
 * {@code compensate.endpoint}.
 */
@Component
public class EndpointReferenceChecker {

    public enum Source { DRAFT, LATEST, IN_FLIGHT_RUN }

    public record Reference(String pipeline, int version, String stepId, String field, Source source) {
    }

    private record Definition(String code, int version, String content, boolean latest, boolean inFlight) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public EndpointReferenceChecker(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Reference> referencesTo(String endpoint) {
        List<Definition> definitions = jdbc.sql("""
                SELECT d.code, d.version, d.content,
                       CASE WHEN d.version = (SELECT MAX(v.version) FROM pipeline_definition v
                                              WHERE v.code = d.code AND v.version > 0) THEN 1 ELSE 0 END AS latest,
                       CASE WHEN EXISTS (SELECT 1 FROM pipeline_run r WHERE r.pipeline_code = d.code
                                         AND r.pipeline_version = d.version AND r.ended_at IS NULL)
                            THEN 1 ELSE 0 END AS in_flight
                FROM pipeline_definition d
                ORDER BY d.code, d.version""")
                .query((rs, i) -> new Definition(rs.getString("code"), rs.getInt("version"), rs.getString("content"),
                        rs.getInt("latest") == 1, rs.getInt("in_flight") == 1))
                .list();
        List<Reference> references = new ArrayList<>();
        for (Definition d : definitions) {
            Source source = d.version() == 0 ? Source.DRAFT
                    : d.latest() ? Source.LATEST : d.inFlight() ? Source.IN_FLIGHT_RUN : null;
            if (source != null) {
                collect(d, source, endpoint, references);
            }
        }
        return references;
    }

    private void collect(Definition d, Source source, String endpoint, List<Reference> out) {
        JsonNode root;
        try {
            root = json.readTree(d.content());
        } catch (Exception e) {
            return;
        }
        for (JsonNode step : root.path("steps")) {
            String stepId = step.path("id").asText(null);
            if (endpoint.equals(step.path("endpoint").asText(null))) {
                out.add(new Reference(d.code(), d.version(), stepId, "endpoint", source));
            }
            if (endpoint.equals(step.path("compensate").path("endpoint").asText(null))) {
                out.add(new Reference(d.code(), d.version(), stepId, "compensate.endpoint", source));
            }
        }
    }
}
