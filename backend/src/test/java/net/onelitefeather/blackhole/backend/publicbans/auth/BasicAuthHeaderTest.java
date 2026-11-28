package net.onelitefeather.blackhole.backend.publicbans.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BasicAuthHeaderTest {

    @Test
    void aMissingHeaderIsClassifiedAsMissing() {
        assertEquals(BasicAuthHeader.Kind.MISSING, BasicAuthHeader.parse(null).kind(), "no Authorization header is missing");
    }

    @Test
    void aBearerSchemeIsClassifiedAsMalformedForBasicAuth() {
        assertEquals(BasicAuthHeader.Kind.MALFORMED, BasicAuthHeader.parse("Bearer abc.def.ghi").kind(),
                "a bearer token is not a basic credential");
    }

    @Test
    void malformedBase64IsClassifiedAsMalformed() {
        assertEquals(BasicAuthHeader.Kind.MALFORMED, BasicAuthHeader.parse("Basic !!!not-base64!!!").kind(),
                "base64 that does not decode is malformed");
    }

    @Test
    void aMissingColonIsClassifiedAsMalformed() {
        assertEquals(BasicAuthHeader.Kind.MALFORMED, BasicAuthHeader.parse(basic("websiteonly")).kind(),
                "a pair without a colon has no password");
    }

    @Test
    void aValidPairIsClassifiedAsValidWithItsUserAndPassword() {
        BasicAuthHeader header = BasicAuthHeader.parse(basic("website:s3cret"));

        assertEquals(BasicAuthHeader.Kind.VALID, header.kind(), "a user and a password form a valid basic credential");
        assertEquals("website", header.user(), "the user is the part before the first colon");
        assertEquals("s3cret", header.password(), "the password is the part after the first colon");
    }

    @Test
    void aPasswordThatContainsColonsKeepsEverythingAfterTheFirstColon() {
        BasicAuthHeader header = BasicAuthHeader.parse(basic("website:a:b:c"));

        assertEquals("a:b:c", header.password(), "only the first colon separates the user from the password");
    }

    @Test
    void theSchemeNameIsCaseInsensitive() {
        BasicAuthHeader header = BasicAuthHeader.parse("basic " + Base64.getEncoder().encodeToString("website:pw".getBytes(StandardCharsets.UTF_8)));

        assertEquals(BasicAuthHeader.Kind.VALID, header.kind(), "the scheme name is case-insensitive per RFC 7235");
    }

    @Test
    void aMalformedCredentialCarriesNoPassword() {
        assertNull(BasicAuthHeader.parse(basic("websiteonly")).password(), "a credential without a colon has no password");
    }

    private static String basic(String pair) {
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
