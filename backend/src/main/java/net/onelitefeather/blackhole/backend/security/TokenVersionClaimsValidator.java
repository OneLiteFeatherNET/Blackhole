package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Singleton;

/**
 * Accepts only Entra v2 access tokens, meaning tokens whose {@code ver} claim is {@code 2.0}. A v1
 * token, or one without a {@code ver} claim, is refused so its different claim layout (for example
 * {@code appid} instead of {@code azp}) can never be mistaken for a v2 token.
 */
@Singleton
public class TokenVersionClaimsValidator implements GenericJwtClaimsValidator<Object> {

    static final String VERSION_CLAIM = "ver";
    static final String REQUIRED_VERSION = "2.0";

    @Override
    public boolean validate(Claims claims, Object request) {
        return REQUIRED_VERSION.equals(claims.get(VERSION_CLAIM));
    }
}
