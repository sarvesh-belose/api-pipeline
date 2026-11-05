package testsupport.engine;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.definition.PipelineDefinition;

/**
 * Pipeline fixtures shared by engine tests (R1 plan §7.3), including the headline reorder pair: API1→2→3→4→5 and,
 * by reordering only the list, API1→2→5→4→3 (US-3.2, US-3.3). Every step calls {@code demo-svc}; mapping
 * expressions use only {@code api1}'s and {@code api2}'s bodies, so both orders are data-safe.
 */
public final class TestPipelines {

    public static final String ENDPOINT = "demo-svc";

    private TestPipelines() {
    }

    /** The five steps in the given list order, each depending implicitly on the one before. */
    public static ObjectNode sequential(String code, String... order) {
        ObjectNode doc = DslJsonMapper.get().createObjectNode();
        doc.put("dslVersion", 1);
        doc.put("code", code);
        ArrayNode steps = doc.putArray("steps");
        for (String id : order) {
            ObjectNode step = steps.addObject();
            step.put("id", id);
            step.put("endpoint", ENDPOINT);
            step.put("method", "POST");
            step.put("path", "/" + id);
            step.put("body", id.equals("api1") ? "{ 'name': input.name }" : "{ 'customerId': steps.api1.body.id }");
        }
        return doc;
    }

    public static ObjectNode onboardV1() {
        return sequential("DEMO_ONBOARD", "api1", "api2", "api3", "api4", "api5");
    }

    public static ObjectNode onboardReordered() {
        return sequential("DEMO_ONBOARD", "api1", "api2", "api5", "api4", "api3");
    }

    /** api3 reads api4's body but is listed before it: the reorder that must be rejected (US-3.2). */
    public static ObjectNode onboardUnsafeReorder() {
        ObjectNode doc = sequential("DEMO_ONBOARD", "api1", "api2", "api3", "api4", "api5");
        ((ObjectNode) doc.withArray("steps").get(2)).put("body", "{ 'account': steps.api4.body.accountNo }");
        return doc;
    }

    public static PipelineDefinition bind(JsonNode doc) {
        try {
            return DslJsonMapper.get().treeToValue(doc, PipelineDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<String> ids(JsonNode doc) {
        List<String> ids = new ArrayList<>();
        doc.path("steps").forEach(s -> ids.add(s.path("id").asText()));
        return ids;
    }
}
