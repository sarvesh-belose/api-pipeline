package com.crisil.apiorch.observability;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Copies the submitting thread's MDC onto the worker (US-1.3 AC3), for the {@code step-io} executor (US-4.3) and
 * the scheduler threads, and restores the worker's previous MDC afterwards so pooled threads never leak context.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(captured);
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
