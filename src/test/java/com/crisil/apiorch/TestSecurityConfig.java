package com.crisil.apiorch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import testsupport.security.TestJwt;

/**
 * The {@code test} profile's JWT decoder: the per-JVM test key, with the same checks production applies through
 * {@code issuer-uri} and {@code audiences} (signature, expiry, issuer, audience). Component-scanned from the test
 * classpath into every test context; Boot's own decoder backs off.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestSecurityConfig {

    @Bean
    JwtDecoder testJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwt.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(TestJwt.ISSUER),
                new JwtClaimValidator<java.util.List<String>>("aud",
                        aud -> aud != null && aud.contains(TestJwt.AUDIENCE))));
        return decoder;
    }
}
