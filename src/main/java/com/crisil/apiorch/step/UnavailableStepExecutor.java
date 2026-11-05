package com.crisil.apiorch.step;

import org.springframework.stereotype.Component;

/**
 * Placeholder until {@code HttpStepExecutor} (US-4.3): every step fails with {@code STEP_EXECUTOR_UNAVAILABLE}, so
 * runs reach a defined end instead of hanging. Engine tests replace it with a {@code @Primary} stub.
 */
@Component
public class UnavailableStepExecutor implements StepExecutor {

    @Override
    public StepOutcome execute(StepInvocation invocation) {
        return StepOutcome.failed(null, "STEP_EXECUTOR_UNAVAILABLE",
                "HTTP step execution arrives with US-4.3", 0, 0);
    }
}
