package testsupport.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Signed test JWTs (US-1.2). One RSA key pair per JVM, generated at class load, so every test context (including
 * the separately started cluster nodes) verifies the same tokens through {@code TestSecurityConfig}. Test only;
 * nothing here is a real credential.
 */
public final class TestJwt {

    public static final String ISSUER = "https://idp.test.apiorch";
    public static final String AUDIENCE = "apiorch";
    public static final String ALL_ROLES_SUBJECT = "test-admin";

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();

    private TestJwt() {
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    /** A valid token for {@code subject} with {@code roles} in the {@code roles} claim. */
    public static String token(String subject, String... roles) {
        return builder(subject).claim("roles", List.of(roles)).sign();
    }

    /** All three roles; the default identity of {@code testsupport.web.Http}. */
    public static String allRoles() {
        return token(ALL_ROLES_SUBJECT, "apiorch.run", "apiorch.admin", "apiorch.ops");
    }

    public static Builder builder(String subject) {
        return new Builder(subject);
    }

    /** Builds tokens with deliberate defects for validation tests. */
    public static final class Builder {

        private final JWTClaimsSet.Builder claims;
        private boolean wrongKey;

        private Builder(String subject) {
            Instant now = Instant.now();
            claims = new JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(AUDIENCE)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
                    .jwtID(UUID.randomUUID().toString());
        }

        public Builder claim(String name, Object value) {
            claims.claim(name, value);
            return this;
        }

        public Builder issuer(String issuer) {
            claims.issuer(issuer);
            return this;
        }

        public Builder audience(String audience) {
            claims.audience(audience);
            return this;
        }

        public Builder expiredSecondsAgo(long seconds) {
            Instant now = Instant.now();
            claims.issueTime(Date.from(now.minusSeconds(seconds + 600))).expirationTime(Date.from(now.minusSeconds(seconds)));
            return this;
        }

        public Builder signedByUnknownKey() {
            wrongKey = true;
            return this;
        }

        public String sign() {
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
                jwt.sign(new RSASSASigner((RSAPrivateKey) (wrongKey ? OTHER_KEYS : KEYS).getPrivate()));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
