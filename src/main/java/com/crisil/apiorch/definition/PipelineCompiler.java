package com.crisil.apiorch.definition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crisil.apiorch.expression.CompiledExpression;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;

/**
 * Turns a valid {@link PipelineDefinition} into a {@link CompiledPipeline}: effective dependencies, dependents, a
 * topological order and every expression compiled once (US-3.3, US-4.3). Pure; US-3.2 reuses it to report
 * expression errors with JSON pointers. Assumes {@link DefinitionValidator} found no unknown steps or cycles.
 */
public final class PipelineCompiler {

    private final ExpressionEvaluator evaluator;

    public PipelineCompiler(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** Every expression error, as issues rooted at {@code pointerPrefix}. Thrown by {@link #compile}. */
    public static final class CompileException extends RuntimeException {

        private final List<DefinitionIssue> issues;

        public CompileException(List<DefinitionIssue> issues) {
            super(issues.size() + " expression error(s)");
            this.issues = List.copyOf(issues);
        }

        public List<DefinitionIssue> issues() {
            return issues;
        }
    }

    private final class Collector {
        final List<DefinitionIssue> issues = new ArrayList<>();

        CompiledExpression compile(String source, String pointer) {
            if (source == null) {
                return null;
            }
            try {
                return evaluator.compile(source);
            } catch (ExpressionException e) {
                String code = e.kind() == ExpressionException.Kind.NOT_ALLOWED && e.code() != null
                        ? e.code() : "EXPRESSION_INVALID";
                issues.add(DefinitionIssue.error(code, null, pointer, e.getMessage()
                        + (e.position() >= 0 ? " (at " + e.position() + ")" : "")));
                return null;
            }
        }

        Map<String, CompiledExpression> compile(Map<String, String> sources, String pointer) {
            if (sources == null) {
                return Map.of();
            }
            Map<String, CompiledExpression> out = new LinkedHashMap<>();
            sources.forEach((name, source) -> {
                CompiledExpression e = compile(source, pointer + "/" + name);
                if (e != null) {
                    out.put(name, e);
                }
            });
            return Collections.unmodifiableMap(out);
        }

        PathTemplate path(String path, String pointer) {
            try {
                return PathTemplate.parse(path);
            } catch (IllegalArgumentException e) {
                issues.add(DefinitionIssue.error("PATH_INVALID", null, pointer, e.getMessage()));
                return null;
            }
        }

        Map<String, CompiledExpression> pathExpressions(PathTemplate path, String pointer) {
            if (path == null) {
                return Map.of();
            }
            Map<String, CompiledExpression> out = new LinkedHashMap<>();
            for (String source : path.expressions()) {
                CompiledExpression e = compile(source, pointer);
                if (e != null) {
                    out.putIfAbsent(source, e);
                }
            }
            return Collections.unmodifiableMap(out);
        }
    }

    public CompiledPipeline compile(int version, PipelineDefinition d, String pointerPrefix) {
        Collector c = new Collector();
        List<CompiledStep> steps = new ArrayList<>();
        Map<String, CompiledStep> byId = new LinkedHashMap<>();
        for (int i = 0; i < d.steps().size(); i++) {
            StepDefinition s = d.steps().get(i);
            String at = pointerPrefix + "/steps/" + i;
            PathTemplate path = c.path(s.path(), at + "/path");
            CompiledStep.Compensation compensation = null;
            if (s.compensate() != null) {
                StepDefinition.CompensateSpec cs = s.compensate();
                PathTemplate cpath = c.path(cs.path(), at + "/compensate/path");
                compensation = new CompiledStep.Compensation(cs.endpoint() != null ? cs.endpoint() : s.endpoint(),
                        cs.method(), cpath, c.pathExpressions(cpath, at + "/compensate/path"),
                        c.compile(cs.query(), at + "/compensate/query"),
                        c.compile(cs.headers(), at + "/compensate/headers"),
                        c.compile(cs.body(), at + "/compensate/body"));
            }
            CompiledStep step = new CompiledStep(i, s, List.copyOf(DefinitionValidator.effectiveDependsOn(d, i)), path,
                    c.pathExpressions(path, at + "/path"),
                    c.compile(s.query(), at + "/query"),
                    c.compile(s.headers(), at + "/headers"),
                    c.compile(s.body(), at + "/body"),
                    c.compile(s.when(), at + "/when"),
                    c.compile(s.successWhen(), at + "/successWhen"),
                    c.compile(s.fallback(), at + "/fallback"),
                    s.callback() != null ? c.compile(s.callback().successWhen(), at + "/callback/successWhen") : null,
                    compensation);
            steps.add(step);
            byId.put(s.id(), step);
        }
        CompiledExpression validWhen = d.input() != null
                ? c.compile(d.input().validWhen(), pointerPrefix + "/input/validWhen") : null;
        CompiledExpression output = c.compile(d.output(), pointerPrefix + "/output");
        if (!c.issues.isEmpty()) {
            throw new CompileException(c.issues);
        }
        Map<String, Set<String>> dependents = dependents(steps);
        return new CompiledPipeline(d.code(), version, d.effectiveDslVersion(), d, List.copyOf(steps),
                Collections.unmodifiableMap(byId), dependents, topologicalOrder(steps, dependents), validWhen, output);
    }

    private static Map<String, Set<String>> dependents(List<CompiledStep> steps) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        steps.forEach(s -> out.put(s.id(), new LinkedHashSet<>()));
        for (CompiledStep s : steps) {
            for (String dep : s.dependsOn()) {
                out.get(dep).add(s.id());
            }
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        out.forEach((k, v) -> frozen.put(k, Collections.unmodifiableSet(v)));
        return Collections.unmodifiableMap(frozen);
    }

    /** Kahn's algorithm, releasing ready steps in list order. */
    static List<String> topologicalOrder(List<CompiledStep> steps, Map<String, Set<String>> dependents) {
        Map<String, Integer> remaining = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (CompiledStep s : steps) {
            remaining.put(s.id(), s.dependsOn().size());
            if (s.dependsOn().isEmpty()) {
                ready.add(s.id());
            }
        }
        Map<String, Integer> listIndex = new HashMap<>();
        steps.forEach(s -> listIndex.put(s.id(), s.index()));
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            List<String> released = new ArrayList<>();
            for (String next : dependents.get(id)) {
                if (remaining.merge(next, -1, Integer::sum) == 0) {
                    released.add(next);
                }
            }
            released.sort((a, b) -> Integer.compare(listIndex.get(a), listIndex.get(b)));
            ready.addAll(released);
        }
        if (order.size() != steps.size()) {
            throw new IllegalStateException("Dependency cycle; validate before compiling");
        }
        return List.copyOf(order);
    }
}
