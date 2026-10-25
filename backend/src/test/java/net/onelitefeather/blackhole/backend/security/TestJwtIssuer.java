package net.onelitefeather.blackhole.backend.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signs access tokens for tests with its own RSA key, so no test needs Entra, the mock OIDC server
 * or any network. Create one per test class (or per test); the key is never shared. Token times come
 * from the injected {@link Clock}. The matching public key is handed to the application as a
 * JWKS through {@link #contextProperties()}, picked up by {@link TestJwksSignature}.
 */
public final class TestJwtIssuer {

    /** The issuer and audience the test environment accepts (the defaults of {@code application.yml}). */
    public static final String ISSUER = "http://localhost:18090/blackhole";
    public static final String AUDIENCE = "blackhole-api";

    /** The group object ids behind the default role mapping of {@code application.yml}. */
    public static final String ADMIN_GROUP = "5d0c0a00-0000-4000-8000-000000000001";
    public static final String MODERATOR_GROUP = "5d0c0a00-0000-4000-8000-000000000002";
    public static final String VIEWER_GROUP = "5d0c0a00-0000-4000-8000-000000000003";
    public static final String METRICS_GROUP = "5d0c0a00-0000-4000-8000-000000000004";

    public static final String PROXY_ROLE = "Blackhole.Proxy";
    public static final String METRICS_ROLE = "Blackhole.Metrics";
    public static final String DISCORD_ROLE = "Blackhole.Discord";

    private static final Duration LIFETIME = Duration.ofHours(1);

    private final Clock clock;
    private final RSAKey key;

    public TestJwtIssuer(Clock clock) {
        this.clock = clock;
        this.key = newKey("test-key");
    }

    private static RSAKey newKey(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate a test key", e);
        }
    }

    /** Properties that make the application trust this issuer's key. */
    public Map<String, Object> contextProperties() {
        return Map.of("blackhole.test.jwks", new JWKSet(this.key.toPublicJWK()).toString());
    }

    /** A token under construction; start from {@link #delegated()} or {@link #appOnly()}. */
    public Token delegated() {
        return base().claim("scp", "access_as_user");
    }

    public Token appOnly() {
        return base();
    }

    public String admin() {
        return delegated().groups(ADMIN_GROUP).sign();
    }

    public String moderator() {
        return delegated().groups(MODERATOR_GROUP).sign();
    }

    public String viewer() {
        return delegated().groups(VIEWER_GROUP).sign();
    }

    public String noGroup() {
        return delegated().groups().sign();
    }

    public String proxy() {
        return appOnly().roles(PROXY_ROLE).sign();
    }

    public String discord() {
        return appOnly().roles(DISCORD_ROLE).sign();
    }

    public String metrics() {
        return appOnly().roles(METRICS_ROLE).sign();
    }

    private Token base() {
        Instant now = this.clock.instant();
        return new Token(this.key)
                .claim("iss", ISSUER)
                .claim("aud", AUDIENCE)
                .claim("sub", UUID.randomUUID().toString())
                .claim("oid", UUID.randomUUID().toString())
                .claim("name", "Test User")
                .claim("iat", Date.from(now))
                .claim("exp", Date.from(now.plus(LIFETIME)));
    }

    /** Builder for one token. */
    public static final class Token {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private RSAKey signingKey;

        private Token(RSAKey key) {
            this.signingKey = key;
        }

        public Token claim(String name, Object value) {
            this.claims.put(name, value);
            return this;
        }

        public Token without(String name) {
            this.claims.remove(name);
            return this;
        }

        public Token groups(String... groups) {
            return claim("groups", List.of(groups));
        }

        public Token roles(String... roles) {
            return claim("roles", List.of(roles));
        }

        public Token expiredAt(Instant when) {
            return claim("exp", Date.from(when));
        }

        /** Signs with a different key than the one the application trusts. */
        public Token signedByAnotherKey() {
            this.signingKey = newKey("test-key");
            return this;
        }

        public String sign() {
            try {
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(this.signingKey.getKeyID()).build(), claimsSet());
                jwt.sign(new RSASSASigner(this.signingKey));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot sign the test token", e);
            }
        }

        /** A key-confusion token: {@code HS256}, with the application's public key bytes as the HMAC secret. */
        public String hs256WithPublicKeyAsSecret() {
            try {
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(this.signingKey.getKeyID()).build(), claimsSet());
                jwt.sign(new MACSigner(this.signingKey.toRSAPublicKey().getEncoded()));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot sign the key-confusion token", e);
            }
        }

        /** A token with {@code alg: none}, which must always be refused. */
        public String unsigned() {
            return new PlainJWT(claimsSet()).serialize();
        }

        private JWTClaimsSet claimsSet() {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
            this.claims.forEach(builder::claim);
            return builder.build();
        }
    }
}
