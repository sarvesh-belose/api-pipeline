package com.crisil.apiorch.definition;

import static com.crisil.apiorch.definition.DefinitionIssue.error;
import static com.crisil.apiorch.definition.DefinitionIssue.warning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.crisil.apiorch.definition.PipelineDefinition.LoggingSpec;
import com.crisil.apiorch.definition.PipelineDefinition.RetrySpec;
import com.crisil.apiorch.definition.StepDefinition.Method;
import com.crisil.apiorch.definition.StepDefinition.Mode;
import com.crisil.apiorch.definition.StepDefinition.OnFailure;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;
import com.crisil.apiorch.expression.ReferenceScanner;

/**
 * The semantic rules of US-3.2 on a structurally valid definition: graph integrity and order, endpoints, paths,
 * expressions (compiled with the real engine), step references against the execution order (deny-by-default),
 * post-completion steps, feature gates, retry, logging and input rules. Pure: endpoint states are passed in, and
 * nothing is written or called (README §13).
 */
public final class DefinitionValidator {

    /** Build and configuration values the rules depend on. */
    public record Settings(int supportedDslVersion, long maxSyncWaitMs, long defaultRunTimeoutMs, String defaultCapture) {
    }

    private static final Pattern MASK_KEY = Pattern.compile("^[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+|\\[\\*\\])*$");
    private static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern RETRY_TOKEN = Pattern.compile("^(5\\d\\d|5xx|408|429|TIMEOUT|CONNECT_ERROR|IO_ERROR)$");

    private final Settings settings;
    private final ExpressionEvaluator evaluator;

    public DefinitionValidator(Settings settings, ExpressionEvaluator evaluator) {
        this.settings = settings;
        this.evaluator = evaluator;
    }

    /** Omitted {@code dependsOn} means the previous step in the list; none for the first (README §8). */
    public static List<String> effectiveDependsOn(PipelineDefinition d, int stepIndex) {
        StepDefinition s = d.steps().get(stepIndex);
        if (s.dependsOn() != null) {
            return s.dependsOn();
        }
        return stepIndex == 0 ? List.of() : List.of(d.steps().get(stepIndex - 1).id());
    }

    /** @param endpoints registered endpoint names and whether each is enabled */
    public ValidationReport validate(PipelineDefinition d, Map<String, Boolean> endpoints) {
        Run run = new Run(d, endpoints);
        run.check();
        return run.report();
    }

    /** One validation pass; keeps the per-definition state the rules share. */
    private final class Run {

        final PipelineDefinition d;
        final Map<String, Boolean> endpoints;
        final List<DefinitionIssue> errors = new ArrayList<>();
        final List<DefinitionIssue> warnings = new ArrayList<>();
        final Map<String, Integer> index = new LinkedHashMap<>();
        final Map<String, List<String>> deps = new LinkedHashMap<>();
        Map<String, Set<String>> ancestors = Map.of();
        List<List<String>> plan = List.of();
        Set<String> postCompletion = Set.of();
        boolean graphValid = true;

        Run(PipelineDefinition d, Map<String, Boolean> endpoints) {
            this.d = d;
            this.endpoints = endpoints;
        }

        String at(int i) {
            return "/steps/" + i;
        }

        void check() {
            if (d.effectiveDslVersion() > settings.supportedDslVersion()) {
                errors.add(error("DSL_VERSION_UNSUPPORTED", null, "/dslVersion", "dslVersion " + d.effectiveDslVersion()
                        + " is newer than this build supports (" + settings.supportedDslVersion() + ")"));
            }
            graph();
            if (graphValid) {
                ancestors = ancestors();
                plan = levels();
                postCompletion = postCompletion();
                orderWarnings();
            }
            for (int i = 0; i < d.steps().size(); i++) {
                step(i, d.steps().get(i));
            }
            pipelineLevel();
        }

        ValidationReport report() {
            List<String> description = graphValid ? PlanDescriber.describe(d, plan, deps) : List.of();
            return new ValidationReport(errors.isEmpty(), plan, postCompletion, description, List.copyOf(errors),
                    List.copyOf(warnings));
        }

        // ---------------------------------------------------------------- graph

        void graph() {
            for (int i = 0; i < d.steps().size(); i++) {
                String id = d.steps().get(i).id();
                Integer first = index.putIfAbsent(id, i);
                if (first != null) {
                    graphValid = false;
                    errors.add(error("DUPLICATE_STEP_ID", id, at(i) + "/id", "step id '" + id
                            + "' is already used by step " + first));
                }
            }
            for (int i = 0; i < d.steps().size(); i++) {
                StepDefinition s = d.steps().get(i);
                List<String> valid = new ArrayList<>();
                List<String> declared = effectiveDependsOn(d, i);
                for (int j = 0; j < declared.size(); j++) {
                    String dep = declared.get(j);
                    String path = at(i) + "/dependsOn/" + j;
                    if (dep.equals(s.id())) {
                        graphValid = false;
                        errors.add(error("SELF_DEPENDENCY", s.id(), path, "step '" + s.id() + "' depends on itself"));
                    } else if (!index.containsKey(dep)) {
                        graphValid = false;
                        errors.add(error("UNKNOWN_DEPENDENCY", s.id(), path, "step '" + s.id()
                                + "' depends on unknown step '" + dep + "'"));
                    } else {
                        valid.add(dep);
                    }
                }
                deps.putIfAbsent(s.id(), valid);
            }
            List<String> cycle = findCycle();
            if (!cycle.isEmpty()) {
                graphValid = false;
                errors.add(error("CYCLE_DETECTED", cycle.get(0), at(index.get(cycle.get(0))) + "/dependsOn",
                        "dependency cycle: " + String.join(" → ", cycle)));
            }
        }

        List<String> findCycle() {
            Map<String, Integer> state = new HashMap<>();
            for (String start : deps.keySet()) {
                List<String> path = new ArrayList<>();
                List<String> found = dfs(start, state, path);
                if (!found.isEmpty()) {
                    return found;
                }
            }
            return List.of();
        }

        /** State 1 = on the current path, 2 = done. Returns the cycle closed back to its first node. */
        List<String> dfs(String id, Map<String, Integer> state, List<String> path) {
            Integer s = state.get(id);
            if (s != null && s == 2) {
                return List.of();
            }
            if (s != null && s == 1) {
                List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
                java.util.Collections.reverse(cycle);
                // start at the step listed first, so the message reads in list order
                String first = cycle.stream().min(java.util.Comparator.comparing(index::get)).orElseThrow();
                java.util.Collections.rotate(cycle, -cycle.indexOf(first));
                cycle.add(cycle.get(0));
                return cycle;
            }
            state.put(id, 1);
            path.add(id);
            for (String dep : deps.getOrDefault(id, List.of())) {
                List<String> found = dfs(dep, state, path);
                if (!found.isEmpty()) {
                    return found;
                }
            }
            path.remove(path.size() - 1);
            state.put(id, 2);
            return List.of();
        }

        Map<String, Set<String>> ancestors() {
            Map<String, Set<String>> out = new HashMap<>();
            for (String id : deps.keySet()) {
                Set<String> seen = new LinkedHashSet<>();
                java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(deps.get(id));
                while (!todo.isEmpty()) {
                    String next = todo.poll();
                    if (seen.add(next)) {
                        todo.addAll(deps.get(next));
                    }
                }
                out.put(id, seen);
            }
            return out;
        }

        List<List<String>> levels() {
            Map<String, Integer> level = new HashMap<>();
            for (String id : index.keySet()) {
                level(id, level);
            }
            int max = level.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
            List<List<String>> out = new ArrayList<>();
            for (int l = 0; l <= max; l++) {
                List<String> ids = new ArrayList<>();
                for (String id : index.keySet()) {
                    if (level.get(id) == l) {
                        ids.add(id);
                    }
                }
                out.add(List.copyOf(ids));
            }
            return List.copyOf(out);
        }

        int level(String id, Map<String, Integer> memo) {
            Integer known = memo.get(id);
            if (known != null) {
                return known;
            }
            int l = 0;
            for (String dep : deps.get(id)) {
                l = Math.max(l, level(dep, memo) + 1);
            }
            memo.put(id, l);
            return l;
        }

        Set<String> postCompletion() {
            Set<String> out = new LinkedHashSet<>();
            for (StepDefinition s : d.steps()) {
                if (s.effectiveMode() == Mode.ASYNC_CALLBACK && !s.effectiveBlocksRunCompletion()) {
                    out.add(s.id());
                }
            }
            for (String id : index.keySet()) {
                for (String root : List.copyOf(out)) {
                    if (ancestors.get(id).contains(root)) {
                        out.add(id);
                    }
                }
            }
            return Set.copyOf(out);
        }

        void orderWarnings() {
            for (int i = 0; i < d.steps().size(); i++) {
                StepDefinition s = d.steps().get(i);
                for (String anc : ancestors.get(s.id())) {
                    if (index.get(anc) > i) {
                        warnings.add(warning("LIST_ORDER_NOT_EXECUTION_ORDER", s.id(), at(i) + "/dependsOn",
                                s.id() + " is listed before " + anc + " but runs after it"));
                    }
                }
            }
            for (List<String> level : plan) {
                boolean explicit = level.stream().anyMatch(id -> d.steps().get(index.get(id)).dependsOn() != null);
                if (level.size() > 1 && explicit) {
                    warnings.add(warning("STEPS_RUN_IN_PARALLEL", level.get(0), null,
                            String.join(", ", level) + " run in parallel"));
                }
            }
        }

        // ---------------------------------------------------------------- steps

        void step(int i, StepDefinition s) {
            String at = at(i);
            endpoint(s.id(), s.endpoint(), at + "/endpoint");
            path(s.id(), s.path(), at + "/path", false);
            expressions(s.id(), s.query(), at + "/query", false);
            expressions(s.id(), s.headers(), at + "/headers", false);
            expression(s.id(), s.body(), at + "/body", false);
            expression(s.id(), s.when(), at + "/when", false);
            expression(s.id(), s.successWhen(), at + "/successWhen", false);
            expression(s.id(), s.fallback(), at + "/fallback", false);
            if (s.callback() != null) {
                expression(s.id(), s.callback().successWhen(), at + "/callback/successWhen", false);
            }
            if (s.compensate() != null) {
                if (s.compensate().endpoint() != null) {
                    endpoint(s.id(), s.compensate().endpoint(), at + "/compensate/endpoint");
                }
                path(s.id(), s.compensate().path(), at + "/compensate/path", true);
                expressions(s.id(), s.compensate().query(), at + "/compensate/query", true);
                expressions(s.id(), s.compensate().headers(), at + "/compensate/headers", true);
                expression(s.id(), s.compensate().body(), at + "/compensate/body", true);
            }
            async(s, at);
            if (s.effectiveOnFailure() == OnFailure.FALLBACK && (s.fallback() == null || s.fallback().isBlank())) {
                errors.add(error("FALLBACK_MISSING", s.id(), at + "/fallback", "onFailure FALLBACK needs a fallback"));
            }
            if (postCompletion.contains(s.id()) && s.effectiveOnFailure() == OnFailure.FAIL_RUN) {
                errors.add(error("POST_COMPLETION_STEP_MUST_NOT_FAIL_RUN", s.id(), at + "/onFailure", "step '"
                        + s.id() + "' runs after the run may already be complete, so it must not use FAIL_RUN"));
            }
            if (postCompletion.contains(s.id()) && s.effectiveMode() != Mode.ASYNC_CALLBACK) {
                String trigger = ancestors.get(s.id()).stream().filter(postCompletion::contains)
                        .filter(a -> d.steps().get(index.get(a)).effectiveMode() == Mode.ASYNC_CALLBACK)
                        .findFirst().orElse("an async step");
                warnings.add(warning("STEP_RUNS_AFTER_COMPLETION", s.id(), at, s.id() + " runs only after "
                        + trigger + "'s callback, possibly after the run is reported complete"));
            }
            features(s, at);
            retry(s, at);
            logging(s.id(), s.logging(), at + "/logging");
            Long timeout = s.timeoutMs() != null ? Long.valueOf(s.timeoutMs())
                    : d.defaults() != null && d.defaults().timeoutMs() != null ? Long.valueOf(d.defaults().timeoutMs()) : null;
            long runTimeout = d.runTimeoutMs() != null ? d.runTimeoutMs() : settings.defaultRunTimeoutMs();
            if (timeout != null && timeout > runTimeout) {
                warnings.add(warning("STEP_TIMEOUT_EXCEEDS_RUN_TIMEOUT", s.id(), at + "/timeoutMs", "timeout " + timeout
                        + " ms is longer than the run timeout " + runTimeout + " ms"));
            }
            if (s.body() != null && (s.method() == Method.GET || s.method() == Method.DELETE)) {
                warnings.add(warning("BODY_ON_GET_OR_DELETE", s.id(), at + "/body", s.method() + " with a body"));
            }
        }

        void endpoint(String stepId, String name, String path) {
            Boolean enabled = endpoints.get(name);
            if (enabled == null) {
                errors.add(error("UNKNOWN_ENDPOINT", stepId, path, "endpoint '" + name + "' is not registered"));
            } else if (!enabled) {
                errors.add(error("ENDPOINT_DISABLED", stepId, path, "endpoint '" + name + "' is disabled"));
            }
        }

        void path(String stepId, String raw, String pointer, boolean compensation) {
            PathTemplate template;
            try {
                template = PathTemplate.parse(raw);
            } catch (IllegalArgumentException e) {
                errors.add(error("PATH_INVALID", stepId, pointer, e.getMessage()));
                return;
            }
            boolean bad = raw.length() > 2048 || raw.contains("//") || raw.contains("://");
            for (List<PathTemplate.Part> segment : template.segments()) {
                for (PathTemplate.Part part : segment) {
                    if (!part.expression() && (part.text().contains("?") || part.text().contains("#")
                            || part.text().equals("..") || part.text().equals("."))) {
                        bad = true;
                    }
                }
            }
            if (bad) {
                errors.add(error("PATH_INVALID", stepId, pointer,
                        "path must be relative to the endpoint: no scheme, host, '..', '//', '?' or '#', at most 2048"));
            }
            for (String expr : template.expressions()) {
                expression(stepId, expr, pointer, compensation);
            }
        }

        void expressions(String stepId, Map<String, String> sources, String pointer, boolean compensation) {
            if (sources != null) {
                sources.forEach((name, source) -> expression(stepId, source, pointer + "/" + name, compensation));
            }
        }

        boolean compiles(String stepId, String source, String pointer, String invalidCode) {
            try {
                evaluator.compile(source);
                return true;
            } catch (ExpressionException e) {
                String code = e.kind() == ExpressionException.Kind.NOT_ALLOWED && e.code() != null ? e.code() : invalidCode;
                String detail = e.getMessage() + (e.code() != null && e.kind() != ExpressionException.Kind.NOT_ALLOWED
                        ? " (" + e.code() + (e.position() >= 0 ? " at " + e.position() : "") + ")" : "");
                errors.add(error(code, stepId, pointer, detail));
                return false;
            }
        }

        void expression(String stepId, String source, String pointer, boolean compensation) {
            if (source == null || !compiles(stepId, source, pointer, "EXPRESSION_INVALID")) {
                return;
            }
            ReferenceScanner.References refs = ReferenceScanner.scan(source);
            if (refs.dynamic()) {
                errors.add(error("DYNAMIC_STEP_REFERENCE", stepId, pointer, "Step '" + stepId + "' reads steps "
                        + "dynamically, which cannot be checked against the execution order. Name the step directly, "
                        + "for example steps.api2.body.ref."));
            }
            for (String ref : refs.steps()) {
                if (!index.containsKey(ref)) {
                    errors.add(error("UNKNOWN_STEP_REFERENCE", stepId, pointer, "Step '" + stepId + "' reads steps."
                            + ref + ", but there is no step '" + ref + "'"));
                } else if (ref.equals(stepId)) {
                    if (!compensation) {
                        errors.add(error("SELF_REFERENCE", stepId, pointer, "Step '" + stepId
                                + "' reads its own response before it has one"));
                    }
                } else if (graphValid && !ancestors.get(stepId).contains(ref)) {
                    boolean later = index.get(ref) > index.get(stepId);
                    errors.add(error("FORWARD_REFERENCE", stepId, pointer, "Step '" + stepId + "' reads steps." + ref
                            + " but '" + ref + "' is not an ancestor of '" + stepId + "' (" + (later ? "it runs later"
                            : "it is not upstream") + "). Move " + ref + " before " + stepId + " or add it to "
                            + stepId + ".dependsOn."));
                }
            }
        }

        void async(StepDefinition s, String at) {
            if (s.effectiveMode() == Mode.ASYNC_CALLBACK && (s.callback() == null || s.callback().timeoutMs() == null)) {
                errors.add(error("ASYNC_CALLBACK_MISSING", s.id(), at + "/callback",
                        "an ASYNC_CALLBACK step needs callback.timeoutMs"));
            }
            if (s.effectiveMode() == Mode.SYNC && s.callback() != null) {
                errors.add(error("CALLBACK_ON_SYNC_STEP", s.id(), at + "/callback", "callback is only for ASYNC_CALLBACK"));
            }
        }

        void features(StepDefinition s, String at) {
            if (!FeatureRegistry.ASYNC_STEPS) {
                if (s.mode() == Mode.ASYNC_CALLBACK) {
                    gated(s.id(), at + "/mode", "ASYNC_STEPS");
                }
                if (s.callback() != null) {
                    gated(s.id(), at + "/callback", "ASYNC_STEPS");
                }
                if (s.blocksRunCompletion() != null) {
                    gated(s.id(), at + "/blocksRunCompletion", "ASYNC_STEPS");
                }
            }
            if (!FeatureRegistry.CONDITIONS && s.when() != null) {
                gated(s.id(), at + "/when", "CONDITIONS");
            }
            if (!FeatureRegistry.COMPENSATION && s.compensate() != null) {
                gated(s.id(), at + "/compensate", "COMPENSATION");
            }
        }

        void gated(String stepId, String path, String feature) {
            errors.add(error("FEATURE_NOT_AVAILABLE", stepId, path, "feature " + feature
                    + " is not available in this release"));
        }

        void retry(StepDefinition s, String at) {
            RetrySpec retry = s.retry() != null ? s.retry() : d.defaults() != null ? d.defaults().retry() : null;
            if (s.retry() != null && s.retry().retryOn() != null) {
                for (int j = 0; j < s.retry().retryOn().size(); j++) {
                    String token = s.retry().retryOn().get(j);
                    if (!RETRY_TOKEN.matcher(token).matches()) {
                        errors.add(error("RETRY_ON_INVALID", s.id(), at + "/retry/retryOn/" + j, "'" + token
                                + "' cannot be retried; use 5xx codes, 408, 429, TIMEOUT, CONNECT_ERROR or IO_ERROR"));
                    }
                }
            }
            if (retry != null && retry.maxAttempts() != null && retry.maxAttempts() > 1 && !s.effectiveIdempotent()) {
                warnings.add(warning("RETRY_WITHOUT_IDEMPOTENCY", s.id(), at + "/retry", "step '" + s.id()
                        + "' is not idempotent, so only failures before the request is sent will be retried"));
            }
        }

        void logging(String stepId, LoggingSpec logging, String pointer) {
            if (logging != null && logging.maskKeys() != null) {
                for (int j = 0; j < logging.maskKeys().size(); j++) {
                    if (!MASK_KEY.matcher(logging.maskKeys().get(j)).matches()) {
                        errors.add(error("MASK_KEY_INVALID", stepId, pointer + "/maskKeys/" + j, "'"
                                + logging.maskKeys().get(j) + "' is not a key name or dotted path"));
                    }
                }
            }
        }

        // ---------------------------------------------------------------- pipeline level

        void pipelineLevel() {
            if (d.syncWaitMs() != null && d.syncWaitMs() > settings.maxSyncWaitMs()) {
                errors.add(error("SYNC_WAIT_EXCEEDS_MAX", null, "/syncWaitMs", "syncWaitMs " + d.syncWaitMs()
                        + " is above apiorch.run.max-sync-wait-ms " + settings.maxSyncWaitMs()));
            }
            if (d.input() != null) {
                if (d.input().required() != null) {
                    for (int j = 0; j < d.input().required().size(); j++) {
                        if (!INPUT_NAME.matcher(d.input().required().get(j)).matches()) {
                            errors.add(error("INPUT_SPEC_INVALID", null, "/input/required/" + j, "'"
                                    + d.input().required().get(j) + "' is not a field name"));
                        }
                    }
                }
                if (d.input().validWhen() != null) {
                    compiles(null, d.input().validWhen(), "/input/validWhen", "INPUT_SPEC_INVALID");
                }
            }
            logging(null, d.logging(), "/logging");
            if (d.output() != null && compiles(null, d.output(), "/output", "EXPRESSION_INVALID")) {
                ReferenceScanner.References refs = ReferenceScanner.scan(d.output());
                if (refs.dynamic()) {
                    errors.add(error("DYNAMIC_STEP_REFERENCE", null, "/output", "output reads steps dynamically; "
                            + "name each step directly"));
                }
                for (String ref : refs.steps()) {
                    if (!index.containsKey(ref)) {
                        errors.add(error("UNKNOWN_STEP_REFERENCE", null, "/output", "output reads steps." + ref
                                + ", but there is no step '" + ref + "'"));
                    } else if (postCompletion.contains(ref)) {
                        warnings.add(warning("OUTPUT_REFERENCES_POST_COMPLETION_STEP", ref, "/output", "output reads "
                                + ref + ", which may still be pending when the run completes"));
                    }
                }
            }
            if (d.allowedCallers() == null || d.allowedCallers().isEmpty()) {
                warnings.add(warning("EMPTY_ALLOWED_CALLERS", null, "/allowedCallers",
                        "no caller may start this pipeline (enforced from R2, US-4.8)"));
            }
            boolean stepCapture = d.steps().stream().anyMatch(s -> s.logging() != null && s.logging().capture() != null);
            if ((d.logging() == null || d.logging().capture() == null) && !stepCapture) {
                warnings.add(warning("CAPTURE_LEVEL_NOT_SET", null, "/logging", "no capture level set; the default "
                        + settings.defaultCapture() + " applies"));
            }
        }
    }
}
