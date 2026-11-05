package com.crisil.apiorch.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * The semantic diff of two published versions (US-3.4), in business terms: did the order or plan change, which steps
 * were added or removed, which fields changed per step (dotted paths), and which pipeline settings changed. Steps are
 * matched by id. The <b>effective</b> {@code dependsOn} is compared instead of the declared one, so a pure reorder
 * shows how the dependencies moved. No diff library is used.
 */
public final class DefinitionDiff {

    /** One version's document, its bound definition and its execution plan. */
    public record Side(int version, JsonNode document, PipelineDefinition definition, List<List<String>> plan) {
    }

    public record FieldChange(String field, JsonNode from, JsonNode to) {
    }

    public record StepChange(String id, List<FieldChange> fields) {
    }

    public record Result(int from, int to, boolean orderChanged, List<String> fromOrder, List<String> toOrder,
            List<List<String>> fromPlan, List<List<String>> toPlan, List<String> addedSteps, List<String> removedSteps,
            List<StepChange> changedSteps, List<FieldChange> changedSettings) {
    }

    static final String EFFECTIVE_DEPENDS_ON = "dependsOn(effective)";

    private DefinitionDiff() {
    }

    public static Result diff(Side from, Side to) {
        Map<String, JsonNode> fromSteps = steps(from.document());
        Map<String, JsonNode> toSteps = steps(to.document());
        Map<String, List<String>> fromDeps = effectiveDeps(from.definition());
        Map<String, List<String>> toDeps = effectiveDeps(to.definition());
        List<String> fromOrder = List.copyOf(fromSteps.keySet());
        List<String> toOrder = List.copyOf(toSteps.keySet());

        List<String> added = toOrder.stream().filter(id -> !fromSteps.containsKey(id)).toList();
        List<String> removed = fromOrder.stream().filter(id -> !toSteps.containsKey(id)).toList();
        List<StepChange> changed = new ArrayList<>();
        for (String id : toOrder) {
            if (!fromSteps.containsKey(id)) {
                continue;
            }
            List<FieldChange> fields = new ArrayList<>();
            walk("", without(fromSteps.get(id), "id", "dependsOn"), without(toSteps.get(id), "id", "dependsOn"),
                    fields);
            List<String> before = fromDeps.get(id);
            List<String> after = toDeps.get(id);
            if (!before.equals(after)) {
                fields.add(new FieldChange(EFFECTIVE_DEPENDS_ON, array(before), array(after)));
            }
            if (!fields.isEmpty()) {
                changed.add(new StepChange(id, List.copyOf(fields)));
            }
        }
        List<FieldChange> settings = new ArrayList<>();
        walk("", without(from.document(), "steps", "code"), without(to.document(), "steps", "code"), settings);

        boolean orderChanged = !fromOrder.equals(toOrder) || !from.plan().equals(to.plan());
        return new Result(from.version(), to.version(), orderChanged, fromOrder, toOrder, from.plan(), to.plan(),
                added, removed, List.copyOf(changed), List.copyOf(settings));
    }

    /** Recursive comparison: objects are walked key by key (sorted), everything else is compared as a value. */
    private static void walk(String path, JsonNode a, JsonNode b, List<FieldChange> out) {
        if (a != null && b != null && a.isObject() && b.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                walk(path.isEmpty() ? name : path + "." + name, a.get(name), b.get(name), out);
            }
            return;
        }
        boolean same = a == null ? b == null : a.equals(b);
        if (!same) {
            out.add(new FieldChange(path, a, b));
        }
    }

    private static Map<String, JsonNode> steps(JsonNode document) {
        Map<String, JsonNode> steps = new LinkedHashMap<>();
        document.path("steps").forEach(s -> steps.put(s.path("id").asText(), s));
        return steps;
    }

    private static Map<String, List<String>> effectiveDeps(PipelineDefinition d) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (int i = 0; i < d.steps().size(); i++) {
            deps.put(d.steps().get(i).id(), List.copyOf(DefinitionValidator.effectiveDependsOn(d, i)));
        }
        return deps;
    }

    private static JsonNode without(JsonNode node, String... names) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                .deepCopy();
        copy.remove(List.of(names));
        return copy;
    }

    private static ArrayNode array(List<String> values) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        values.forEach(a::add);
        return a;
    }
}
