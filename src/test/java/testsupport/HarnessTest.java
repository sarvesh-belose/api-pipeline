package testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.PipelineCompiler;
import com.crisil.apiorch.engine.Lease;
import com.crisil.apiorch.step.StepInvocation;
import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.FakeExpressionEvaluator;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.time.MutableClock;

/** The shared harnesses behave as documented, so engine tests can rely on them. */
class HarnessTest {

    @Test
    void mutableClockMovesOnlyWhenTold() {
        MutableClock clock = MutableClock.at("2026-11-16T09:00:00Z");
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-16T09:00:00Z"));
        clock.advance(Duration.ofSeconds(30));
        assertThat(clock.millis()).isEqualTo(Instant.parse("2026-11-16T09:00:30Z").toEpochMilli());
        MutableClock zoned = (MutableClock) clock.withZone(ZoneId.of("Asia/Kolkata"));
        clock.set(Instant.EPOCH);
        assertThat(zoned.instant()).isEqualTo(Instant.EPOCH);
        assertThat(zoned.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void stubExecutorScriptsHoldsAndRecords() throws Exception {
        CompiledPipeline p = new PipelineCompiler(new FakeExpressionEvaluator())
                .compile(1, TestPipelines.bind(TestPipelines.onboardV1()), "/definition");
        StubStepExecutor stub = new StubStepExecutor()
                .on("api2", StepOutcome.failed(503, "HTTP_ERROR", "down", 3, 40))
                .hold("api3");
        Lease lease = new Lease(UUID.randomUUID(), "node-a", 1);
        java.util.function.Function<String, StepInvocation> inv = id -> new StepInvocation(lease, "DEMO_ONBOARD", 1,
                "c-1", p.step(id), 1, null, Instant.EPOCH, null);

        assertThat(stub.execute(inv.apply("api1")).body().path("stepId").asText()).isEqualTo("api1");
        assertThat(stub.execute(inv.apply("api2")).errorCode()).isEqualTo("HTTP_ERROR");
        CompletableFuture<StepOutcome> held = CompletableFuture.supplyAsync(() -> stub.execute(inv.apply("api3")));
        Thread.sleep(100);
        assertThat(held).isNotDone();
        stub.release("api3");
        assertThat(held.get(5, TimeUnit.SECONDS).status()).isEqualTo(StepOutcome.Status.SUCCEEDED);
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2", "api3");
        assertThat(stub.invocations().get(0).runId()).isEqualTo(lease.runId());
        stub.reset();
        assertThat(stub.invocations()).isEmpty();
    }
}
