package com.crisil.apiorch.persistence;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The rotation report (US-7.6 AC3): at startup, how many {@code run_context} rows each key id still holds, with a
 * WARN for rows on a non-active key or stored plain. A database that is down only skips the report (US-1.1 AC3).
 */
@Component
public class ContextKeyReport {

    private static final Logger log = LoggerFactory.getLogger(ContextKeyReport.class);

    private final RunRows rows;
    private final ContextCipher cipher;

    public ContextKeyReport(RunRows rows, ContextCipher cipher) {
        this.rows = rows;
        this.cipher = cipher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        Map<String, Long> byKid;
        try {
            byKid = rows.contextRowsByKid();
        } catch (RuntimeException e) {
            log.warn("run_context key report skipped: {}", e.getClass().getSimpleName());
            return;
        }
        log.info("run_context rows by key id {}", byKid);
        byKid.forEach((kid, count) -> {
            if (kid == null) {
                log.warn("run_context rows stored plain={}", count);
            } else if (!kid.equals(cipher.activeKid())) {
                log.warn("run_context rows on old key kid={} rows={}", kid, count);
            }
        });
    }
}
