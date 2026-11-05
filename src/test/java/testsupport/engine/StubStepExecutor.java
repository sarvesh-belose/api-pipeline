package testsupport.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.step.StepExecutor;
import com.crisil.apiorch.step.StepInvocation;
import com.crisil.apiorch.step.StepOutcome;

/**
 * A scripted {@link StepExecutor} for engine tests (US-4.1, R1 plan §7.3). By default every step succeeds with
 * {@code 200} and body {@code {"stepId": "<id>"}}. Tests script outcomes per step id, hold a step at a latch to
 * create races, and read back the invocation order.
 */
public final class StubStepExecutor implements StepExecutor {

    private final Map<String, Function<StepInvocation, StepOutcome>> scripts = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final List<StepInvocation> invocations = new CopyOnWriteArrayList<>();

    public StubStepExecutor on(String stepId, StepOutcome outcome) {
        scripts.put(stepId, inv -> outcome);
        return this;
    }

    public StubStepExecutor on(String stepId, Function<StepInvocation, StepOutcome> script) {
        scripts.put(stepId, script);
        return this;
    }

    /** {@code stepId} blocks on entry until {@link #release(String)}. */
    public StubStepExecutor hold(String stepId) {
        gates.put(stepId, new CountDownLatch(1));
        return this;
    }

    public void release(String stepId) {
        CountDownLatch gate = gates.remove(stepId);
        if (gate != null) {
            gate.countDown();
        }
    }

    @Override
    public StepOutcome execute(StepInvocation invocation) {
        invocations.add(invocation);
        CountDownLatch gate = gates.get(invocation.step().id());
        if (gate != null) {
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    return StepOutcome.failed(null, "TIMEOUT", "stub gate not released", 1, 30000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return StepOutcome.failed(null, "INTERRUPTED", "stub interrupted", 1, 0);
            }
        }
        Function<StepInvocation, StepOutcome> script = scripts.get(invocation.step().id());
        if (script != null) {
            return script.apply(invocation);
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("stepId", invocation.step().id());
        return StepOutcome.succeeded(200, JsonNodeFactory.instance.objectNode(), body, 1, 1);
    }

    public List<StepInvocation> invocations() {
        return List.copyOf(invocations);
    }

    public List<String> invokedStepIds() {
        return invocations.stream().map(i -> i.step().id()).toList();
    }

    public void reset() {
        scripts.clear();
        gates.values().forEach(CountDownLatch::countDown);
        gates.clear();
        invocations.clear();
    }
}
