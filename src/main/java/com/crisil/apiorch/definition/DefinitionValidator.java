package com.crisil.apiorch.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crisil.apiorch.definition.StepDefinition.Mode;
import com.crisil.apiorch.definition.StepDefinition.OnFailure;

/**
 * Semantic checks on a structurally valid definition (US-3.1 AC4 and AC8; US-3.2 extends it with expression
 * compilation, reorder safety, feature gates and the remaining rules). Pure: the caller passes the known endpoint
 * names, so this package stays free of persistence (README §13).
 */
public final class DefinitionValidator {

    private final int supportedDslVersion;

    public DefinitionValidator(int supportedDslVersion) {
        this.supportedDslVersion = supportedDslVersion;
    }

    public ValidationReport validate(PipelineDefinition d, Set<String> knownEndpoints, String pointerPrefix) {
        List<DefinitionIssue> errors = new ArrayList<>();
        List<DefinitionIssue> warnings = new ArrayList<>();
        String root = pointerPrefix;

        if (d.effectiveDslVersion() > supportedDslVersion) {
            errors.add(new DefinitionIssue("DSL_VERSION_UNSUPPORTED", root + "/dslVersion",
                    "dslVersion " + d.effectiveDslVersion() + " is newer than this build supports ("
                            + supportedDslVersion + ")"));
        }

        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < d.steps().size(); i++) {
            String id = d.steps().get(i).id();
            if (index.putIfAbsent(id, i) != null) {
                errors.add(new DefinitionIssue("DUPLICATE_STEP_ID", root + "/steps/" + i + "/id",
                        "step id '" + id + "' is already used by step " + index.get(id)));
            }
        }

        Map<String, List<String>> deps = new HashMap<>();
        for (int i = 0; i < d.steps().size(); i++) {
            StepDefinition s = d.steps().get(i);
            String at = root + "/steps/" + i;
            List<String> stepDeps = effectiveDependsOn(d, i);
            deps.put(s.id(), stepDeps);
            if (s.dependsOn() != null) {
                for (int j = 0; j < s.dependsOn().size(); j++) {
                    String dep = s.dependsOn().get(j);
                    Integer depIndex = index.get(dep);
                    if (depIndex == null) {
                        errors.add(new DefinitionIssue("UNKNOWN_STEP", at + "/dependsOn/" + j,
                                "depends on unknown step '" + dep + "'"));
                    } else if (dep.equals(s.id())) {
                        errors.add(new DefinitionIssue("CYCLE", at + "/dependsOn/" + j, "depends on itself"));
                    } else if (depIndex > i) {
                        warnings.add(new DefinitionIssue("ORDER_DIFFERS", at + "/dependsOn/" + j,
                                "runs after '" + dep + "', which is listed later; list order and execution order differ"));
                    }
                }
            }
            if (!knownEndpoints.contains(s.endpoint())) {
                errors.add(new DefinitionIssue("UNKNOWN_ENDPOINT", at + "/endpoint",
                        "endpoint '" + s.endpoint() + "' is not registered"));
            }
            if (s.compensate() != null && s.compensate().endpoint() != null
                    && !knownEndpoints.contains(s.compensate().endpoint())) {
                errors.add(new DefinitionIssue("UNKNOWN_ENDPOINT", at + "/compensate/endpoint",
                        "endpoint '" + s.compensate().endpoint() + "' is not registered"));
            }
            if (s.effectiveOnFailure() == OnFailure.FALLBACK && (s.fallback() == null || s.fallback().isBlank())) {
                errors.add(new DefinitionIssue("FALLBACK_REQUIRED", at + "/fallback",
                        "onFailure FALLBACK needs a fallback expression"));
            }
            if (s.effectiveMode() == Mode.ASYNC_CALLBACK && (s.callback() == null || s.callback().timeoutMs() == null)) {
                errors.add(new DefinitionIssue("CALLBACK_REQUIRED", at + "/callback",
                        "an ASYNC_CALLBACK step needs callback.timeoutMs"));
            }
        }

        detectCycles(deps, index, root, errors);
        return ValidationReport.of(errors, warnings);
    }

    /** Omitted {@code dependsOn} means the previous step in the list; none for the first (README §8). */
    public static List<String> effectiveDependsOn(PipelineDefinition d, int stepIndex) {
        StepDefinition s = d.steps().get(stepIndex);
        if (s.dependsOn() != null) {
            return s.dependsOn();
        }
        return stepIndex == 0 ? List.of() : List.of(d.steps().get(stepIndex - 1).id());
    }

    private static void detectCycles(Map<String, List<String>> deps, Map<String, Integer> index, String root,
            List<DefinitionIssue> errors) {
        Set<String> done = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (String start : index.keySet()) {
            visit(start, deps, new LinkedHashMap<>(), done, reported, index, root, errors);
        }
    }

    private static void visit(String id, Map<String, List<String>> deps, LinkedHashMap<String, Boolean> path,
            Set<String> done, Set<String> reported, Map<String, Integer> index, String root,
            List<DefinitionIssue> errors) {
        if (done.contains(id) || !index.containsKey(id)) {
            return;
        }
        if (path.containsKey(id)) {
            List<String> cycle = new ArrayList<>(path.keySet());
            cycle = cycle.subList(cycle.indexOf(id), cycle.size());
            if (cycle.size() > 1 && reported.addAll(cycle)) {
                errors.add(new DefinitionIssue("CYCLE", root + "/steps/" + index.get(id) + "/dependsOn",
                        "dependency cycle: " + String.join(" -> ", cycle) + " -> " + id));
            }
            return;
        }
        path.put(id, Boolean.TRUE);
        for (String dep : deps.getOrDefault(id, List.of())) {
            visit(dep, deps, path, done, reported, index, root, errors);
        }
        path.remove(id);
        done.add(id);
    }
}
