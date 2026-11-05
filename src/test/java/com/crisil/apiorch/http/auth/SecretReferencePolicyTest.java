package com.crisil.apiorch.http.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** US-2.2 AC1b and AC2: references only, env prefix, file confinement including traversal and symlinks. */
class SecretReferencePolicyTest {

    @TempDir Path temp;

    private SecretReferencePolicy policy(Path dir) {
        return new SecretReferencePolicy("APIORCH_SECRET_", dir.toString());
    }

    @Test
    void literalsAreNotReferences() {
        SecretReferencePolicy p = policy(temp);
        assertThat(p.violations("abc123")).containsExactly("secrets must be references (env:NAME or file:/path)");
        assertThat(p.violations(null)).hasSize(1);
        assertThat(p.violations("vault:kv/x")).hasSize(1);
        assertThat(SecretReferencePolicy.isReference("env:X")).isTrue();
    }

    @Test
    void envReferencesNeedThePrefix() {
        SecretReferencePolicy p = policy(temp);
        assertThat(p.violations("env:APIORCH_SECRET_KYC_API_KEY")).isEmpty();
        assertThat(p.violations("env:SPRING_DATASOURCE_PASSWORD")).hasSize(1);
        assertThat(p.violations("env:apiorch_secret_lower")).hasSize(1);
    }

    @Test
    void fileReferencesStayInsideTheSecretsDirectory() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("secrets"));
        Files.writeString(dir.resolve("kyc"), "s3cret");
        SecretReferencePolicy p = policy(dir);
        assertThat(p.violations("file:" + dir.resolve("kyc"))).isEmpty();
        assertThat(p.violations("file:" + dir.resolve("not-yet-there"))).isEmpty();
        assertThat(p.violations("file:" + dir.resolve("../etc/passwd"))).hasSize(1);
        assertThat(p.violations("file:/proc/self/environ")).hasSize(1);
        assertThat(p.violations("file:relative/path")).hasSize(1);
    }

    @Test
    void symlinksOutOfTheDirectoryAreRefused() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("secrets"));
        Path outside = Files.writeString(temp.resolve("outside"), "x");
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // symlinks need extra privileges on some Windows hosts
        }
        assertThat(policy(dir).violations("file:" + link)).hasSize(1);
    }

    @Test
    void canonicalHandlesPathsThatDoNotExist() {
        Path missing = temp.resolve("a/b/c");
        assertThat(SecretReferencePolicy.canonical(missing).endsWith(Path.of("a", "b", "c"))).isTrue();
    }
}
