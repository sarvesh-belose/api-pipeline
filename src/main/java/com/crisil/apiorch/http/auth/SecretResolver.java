package com.crisil.apiorch.http.auth;

import java.util.Optional;

/**
 * Resolves a secret reference such as {@code env:APIORCH_SECRET_KYC} or {@code file:/run/secrets/kyc} to its value
 * (US-2.2). Pluggable, so a Vault resolver can be added without changing endpoints or pipelines. Values are never
 * logged, stored or returned.
 */
public interface SecretResolver {

    /** Empty when the reference cannot be resolved (missing variable, unreadable file). */
    Optional<String> resolve(String ref);
}
