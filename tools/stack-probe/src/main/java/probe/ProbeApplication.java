package probe;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class ProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbeApplication.class, args);
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${probe.jwt.secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(), "HmacSHA256")).build();
    }

    /** Async MVC controller behind the JWT chain: the US-4.1 shape. */
    @RestController
    static class RunController {
        @PostMapping("/api/v1/pipelines/{code}/runs")
        CompletableFuture<ResponseEntity<Map<String, Object>>> start(@PathVariable String code,
                                                                     @RequestBody Map<String, Object> input) {
            return CompletableFuture.supplyAsync(() -> ResponseEntity.ok(Map.of("pipeline", code, "status", "COMPLETED")));
        }
    }

    /** Unauthenticated callback chain: the US-6.2 shape. */
    @RestController
    static class CallbackController {
        @PostMapping("/api/v1/callbacks/{runId}/{stepId}")
        Map<String, Object> receive(@PathVariable String runId, @PathVariable String stepId) {
            return Map.of("status", "ACCEPTED");
        }
    }

    /** ShedLock-guarded scheduled job: the sweeper shape (US-6.4, US-7.8). */
    @Component
    static class Sweeper {
        static final AtomicInteger RUNS = new AtomicInteger();

        @Scheduled(fixedDelay = 200)
        @SchedulerLock(name = "probe-sweeper", lockAtMostFor = "PT5S")
        void sweep() {
            RUNS.incrementAndGet();
        }
    }
}
