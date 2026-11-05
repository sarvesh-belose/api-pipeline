package testsupport.engine;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the step executor with the scripted {@link StubStepExecutor} (US-4.1). {@code @Import} it. */
@TestConfiguration(proxyBeanMethods = false)
public class StubExecutorConfig {

    @Bean
    @Primary
    public StubStepExecutor stubStepExecutor() {
        return new StubStepExecutor();
    }
}
