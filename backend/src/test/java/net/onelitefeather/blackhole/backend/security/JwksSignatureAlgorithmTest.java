package net.onelitefeather.blackhole.backend.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.security.token.jwt.signature.jwks.DefaultJwkValidator;
import io.micronaut.security.token.jwt.signature.jwks.JwkSetFetcher;
import io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production JWKS signature check accepts RS256 and nothing else. Each test builds its own
 * generated key and an in-memory key set, so no network is involved.
 */
class JwksSignatureAlgorithmTest {

    private RSAKey key;
    private Rs256PinnedJwksSignature signature;

    @BeforeEach
    void setUp() throws JOSEException {
        this.key = new RSAKeyGenerator(2048).keyID("unit-key").generate();
        JWKSet keys = new JWKSet(this.key.toPublicJWK());
        JwkSetFetcher<JWKSet> fetcher = new JwkSetFetcher<>() {
            @Override
            public Optional<JWKSet> fetch(String url) {
                return Optional.of(keys);
            }

            @Override
            public Publisher<JWKSet> fetch(String name, String url) {
                return Mono.just(keys);
            }

            @Override
            public void clearCache(String url) {
            }
        };
        this.signature = new Rs256PinnedJwksSignature(new JwksSignatureConfiguration() {
            @Override
            public String getName() {
                return "unit";
            }

            @Override
            public String getUrl() {
                return "http://unused.invalid/jwks";
            }

            @Override
            public KeyType getKeyType() {
                return KeyType.RSA;
            }

            @Override
            @SuppressWarnings("removal")
            public Integer getCacheExpiration() {
                return 60;
            }
        }, new DefaultJwkValidator(), fetcher);
    }

    private JWTClaimsSet claims() {
        return new JWTClaimsSet.Builder().subject("someone").build();
    }

    private boolean verifies(SignedJWT jwt) {
        return Boolean.TRUE.equals(Mono.from(this.signature.verify(jwt)).block());
    }

    private SignedJWT signedWithRsa(JWSAlgorithm algorithm) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(this.key.getKeyID()).build(), claims());
        jwt.sign(new RSASSASigner(this.key));
        return jwt;
    }

    @Test
    void aCorrectRs256TokenIsAccepted() throws JOSEException {
        assertTrue(verifies(signedWithRsa(JWSAlgorithm.RS256)), "RS256 signed with the trusted key");
    }

    @Test
    void anHs256TokenSignedWithThePublicKeyBytesIsRejected() throws JOSEException {
        byte[] secret = this.key.toRSAPublicKey().getEncoded();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(this.key.getKeyID()).build(), claims());
        jwt.sign(new MACSigner(secret));

        assertFalse(verifies(jwt), "key confusion: HMAC with the public key as secret");
    }

    @Test
    void anRs384TokenFromTheTrustedKeyIsRejected() throws JOSEException {
        assertFalse(verifies(signedWithRsa(JWSAlgorithm.RS384)), "only RS256 is allowed, even with the right key");
    }

    @Test
    void aPs256TokenFromTheTrustedKeyIsRejected() throws JOSEException {
        assertFalse(verifies(signedWithRsa(JWSAlgorithm.PS256)), "only RS256 is allowed, even with the right key");
    }

    @Test
    void anAlgNoneTokenCannotBecomeASignedToken() {
        String unsigned = new PlainJWT(claims()).serialize();

        assertThrows(ParseException.class, () -> SignedJWT.parse(unsigned), "alg none is not a signed JWT");
    }
}
