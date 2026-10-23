package net.onelitefeather.blackhole.backend.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.security.token.jwt.signature.SignatureConfiguration;
import io.micronaut.security.token.jwt.signature.jwks.DefaultJwkValidator;
import io.micronaut.security.token.jwt.signature.jwks.JWKSetJwksSignature;
import jakarta.inject.Singleton;

import java.text.ParseException;

/**
 * The {@code test} environment's signature source: a fixed public key set handed in by the test
 * ({@link TestJwtIssuer#contextProperties()}) instead of the issuer's JWKS URL, so verifying a token
 * never touches the network.
 */
@Singleton
@Requires(env = "test")
@Requires(property = "blackhole.test.jwks")
public class TestJwksSignature implements SignatureConfiguration {

    private final JWKSetJwksSignature delegate;

    public TestJwksSignature(@Value("${blackhole.test.jwks}") String jwks) throws ParseException {
        this.delegate = new JWKSetJwksSignature(new DefaultJwkValidator(), JWKSet.parse(jwks));
    }

    @Override
    public String supportedAlgorithmsMessage() {
        return this.delegate.supportedAlgorithmsMessage();
    }

    @Override
    public boolean supports(JWSAlgorithm algorithm) {
        return this.delegate.supports(algorithm);
    }

    @Override
    public boolean verify(SignedJWT jwt) throws JOSEException {
        return this.delegate.verify(jwt);
    }
}
