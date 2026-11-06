package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import com.crisil.apiorch.engine.StepStatus;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.security.Roles;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.TestPipelines;

/** US-7.4 AC8: a timeline of 50 steps with 3 attempts each takes a fixed number of statements (at most 6). */
@SpringBootTest
@ActiveProfiles("test")
class TimelineQueryCountIT {

    static final AtomicInteger STATEMENTS = new AtomicInteger();
    static final ThreadLocal<Boolean> COUNTING = ThreadLocal.withInitial(() -> false);

    /** Counts statements prepared on the counting thread, by wrapping every connection the pool hands out. */
    @TestConfiguration
    static class Counting {
        @Bean
        static BeanPostProcessor countStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                            (p, m, args) -> {
                                Object result = m.invoke(ds, args);
                                return result instanceof Connection c ? counting(c) : result;
                            });
                }
            };
        }

        static Connection counting(Connection c) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (p, m, args) -> {
                        if (COUNTING.get() && (m.getName().equals("prepareStatement")
                                || m.getName().equals("createStatement") || m.getName().equals("prepareCall"))) {
                            STATEMENTS.incrementAndGet();
                        }
                        return m.invoke(c, args);
                    });
        }
    }

    @Autowired JdbcClient jdbc;
    @Autowired RunRows rows;
    @Autowired TimelineService timelines;

    @Test
    void statementsDoNotGrowWithTheRun() {
        String[] ids = new String[50];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "s" + i;
        }
        String code = PublishedFixtures.uniqueCode("COUNT");
        var doc = TestPipelines.sequential(code, ids);
        PublishedFixtures.publish(jdbc, doc, 1);
        UUID runId = UUID.randomUUID();
        rows.insertRun(runId, code, 1, "SYNC", "c-count", "ops-user", null, 0);
        for (String id : ids) {
            rows.insertStep(runId, id, "SYNC");
            rows.finishStep(runId, id, StepStatus.SUCCEEDED, 3, 200, 10L, null, null, false, null);
            for (int n = 1; n <= 3; n++) {
                jdbc.sql("""
                        INSERT INTO step_attempt (id, run_id, step_id, exec_no, attempt_no, kind, event_seq, endpoint_name,
                            method, url, outcome, started_at, duration_ms, request_bytes, response_bytes)
                        VALUES (:id, :run, :step, 1, :n, 'REQUEST', :seq, 'svc', 'GET', 'http://x/y', 'SUCCESS',
                            SYSUTCDATETIME(), 10, 1, 2)""")
                        .param("id", UUID.randomUUID()).param("run", runId).param("step", id).param("n", n)
                        .param("seq", n).update();
            }
        }
        var ops = new UsernamePasswordAuthenticationToken("ops-user", "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + Roles.OPS)));

        COUNTING.set(true);
        STATEMENTS.set(0);
        try {
            var timeline = timelines.timeline(runId.toString(), true, ops);
            assertThat(timeline.calls()).hasSize(150);
        } finally {
            COUNTING.set(false);
        }
        // run, steps, attempts, exchanges, events: the counter must have seen them, and nothing per step
        assertThat(STATEMENTS.get()).isBetween(5, 6);
    }
}
