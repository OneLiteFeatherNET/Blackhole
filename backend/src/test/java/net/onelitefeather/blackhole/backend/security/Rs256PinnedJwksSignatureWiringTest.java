package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.token.jwt.nimbus.ReactiveJwksSignature;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A configured JWKS source is checked by the RS256-pinned signature, not by the library default. */
@Tag("integration")
class Rs256PinnedJwksSignatureWiringTest extends ApiIntegrationTest {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "micronaut.security.token.jwt.signatures.jwks.issuer.url", "http://unused.invalid/jwks",
                "micronaut.security.token.jwt.signatures.jwks.issuer.key-type", "RSA");
    }

    @Test
    void everyJwksSourceIsCheckedByThePinnedSignature() {
        Collection<ReactiveJwksSignature> signatures = this.context.getBeansOfType(ReactiveJwksSignature.class);

        assertEquals(1, signatures.size(), "one JWKS source is configured");
        assertInstanceOf(Rs256PinnedJwksSignature.class, signatures.iterator().next(), "the library default must be replaced");
    }
}
