package com.crisil.apiorch.definition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The execution plan in plain language (US-3.2 AC9), for example
 * {@code "1. api1 (customer-svc POST /customers)"}, {@code "2. api2 (kyc-svc POST /kyc) after api1"} and
 * {@code "3. api5 and api4 in parallel after api2"}.
 */
public final class PlanDescriber {

    private PlanDescriber() {
    }

    public static List<String> describe(PipelineDefinition d, List<List<String>> plan, Map<String, List<String>> deps) {
        List<String> lines = new ArrayList<>();
        for (int level = 0; level < plan.size(); level++) {
            List<String> ids = plan.get(level);
            StringBuilder line = new StringBuilder().append(level + 1).append(". ");
            if (ids.size() == 1) {
                StepDefinition s = step(d, ids.get(0));
                line.append(s.id()).append(" (").append(s.endpoint()).append(' ').append(s.method()).append(' ')
                        .append(s.path()).append(')');
            } else {
                line.append(String.join(", ", ids.subList(0, ids.size() - 1))).append(" and ")
                        .append(ids.get(ids.size() - 1)).append(" in parallel");
            }
            Set<String> after = new LinkedHashSet<>();
            ids.forEach(id -> after.addAll(deps.getOrDefault(id, List.of())));
            if (!after.isEmpty()) {
                line.append(" after ").append(String.join(", ", after));
            }
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }

    private static StepDefinition step(PipelineDefinition d, String id) {
        return d.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
