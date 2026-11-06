package com.crisil.apiorch.run;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.ApiorchMetrics;

/**
 * Admission control (US-5.4): how many runs this node drives, meaning holds a lease on, whatever their status
 * (README §5.1). Only new runs are gated, before they are created; recovered and woken runs are existing work and are
 * counted but never refused, so the count may pass the limit for a while. The count moves with the lease (created,
 * claimed, released, lost) and is set from the leases actually renewed at every renewal, so it cannot drift.
 */
@Component
public class AdmissionControl {

    private static final Logger log = LoggerFactory.getLogger(AdmissionControl.class);

    public static final String REJECTED = "admission-rejected";

    private final AtomicInteger driven = new AtomicInteger();
    private final int max;
    private final ApiorchMetrics metrics;

    public AdmissionControl(@Value("${apiorch.admission.max-in-flight-runs:500}") int max, ApiorchMetrics metrics) {
        this.max = max;
        this.metrics = metrics;
        metrics.runsInflight(driven);
    }

    /** Takes a place for a new run, or refuses it with {@code 429 admission-rejected} (AC3). */
    public void admitNew() {
        while (true) {
            int now = driven.get();
            if (now >= max) {
                metrics.admissionRejected();
                log.warn("admission rejected driven={}", now);
                throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, REJECTED,
                        "apiorch is at capacity on this node; retry later").header("Retry-After", "1");
            }
            if (driven.compareAndSet(now, now + 1)) {
                return;
            }
        }
    }

    /** The new run was not created after all. */
    public void cancelNew() {
        released();
    }

    /** A run claimed by recovery or a wake-up: counted, never refused (AC5). */
    public void claimed() {
        driven.incrementAndGet();
    }

    /** The lease was given up (finished, released) or lost. */
    public void released() {
        driven.updateAndGet(n -> Math.max(0, n - 1));
    }

    /** At every renewal: the leases this node actually still holds. */
    public void reconcile(int owned) {
        driven.set(owned);
    }

    public int driven() {
        return driven.get();
    }
}
