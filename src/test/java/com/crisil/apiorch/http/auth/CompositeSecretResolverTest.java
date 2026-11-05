package com.crisil.apiorch.http.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** US-2.2: env and file resolution, misses, confinement on resolve, and rotation after the cache TTL (AC6). */
class CompositeSecretResolverTest {

    @TempDir Path dir;

    private final AtomicLong nanos = new AtomicLong();

    private CompositeSecretResolver resolver(Map<String, String> env) {
        return new CompositeSecretResolver(new SecretReferencePolicy("APIORCH_SECRET_", dir.toString()), env::get,
                Duration.ofMinutes(5).toMillis(), nanos::get);
    }

    @Test
    void resolvesEnvAndFileReferences() throws Exception {
        Files.writeString(dir.resolve("token"), "  file-value \n");
        CompositeSecretResolver r = resolver(Map.of("APIORCH_SECRET_KEY", "env-value", "APIORCH_SECRET_EMPTY", ""));
        assertThat(r.resolve("env:APIORCH_SECRET_KEY")).hasValue("env-value");
        assertThat(r.resolve("file:" + dir.resolve("token"))).hasValue("file-value");
        assertThat(r.resolve("env:APIORCH_SECRET_MISSING")).isEmpty();
        assertThat(r.resolve("env:APIORCH_SECRET_EMPTY")).isEmpty();
        assertThat(r.resolve("file:" + dir.resolve("absent"))).isEmpty();
    }

    @Test
    void refusesReferencesOutsideThePolicyEvenIfStoredEarlier() {
        CompositeSecretResolver r = resolver(Map.of("SPRING_DATASOURCE_PASSWORD", "db"));
        assertThat(r.resolve("env:SPRING_DATASOURCE_PASSWORD")).isEmpty();
        assertThat(r.resolve("plain")).isEmpty();
    }

    @Test
    void emptyFileIsUnavailable() throws Exception {
        Files.writeString(dir.resolve("blank"), "   ");
        assertThat(resolver(Map.of()).resolve("file:" + dir.resolve("blank"))).isEmpty();
    }

    @Test
    void rotatedFileIsPickedUpAfterTheTtl() throws Exception {
        Path secret = Files.writeString(dir.resolve("rotating"), "v1");
        CompositeSecretResolver r = resolver(Map.of());
        String ref = "file:" + secret;
        assertThat(r.resolve(ref)).hasValue("v1");
        Files.writeString(secret, "v2");
        nanos.addAndGet(Duration.ofMinutes(4).toNanos());
        assertThat(r.resolve(ref)).hasValue("v1");
        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(r.resolve(ref)).hasValue("v2");
    }
}
