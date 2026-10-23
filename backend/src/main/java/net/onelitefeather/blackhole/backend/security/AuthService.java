package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.dto.AuthDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Describes the verified caller. Only the {@code oid} and {@code name} claims are read; email,
 * UPN and preferred username are never looked at.
 */
@Singleton
public class AuthService {

    /** The caller's identity and roles; an empty role list is a valid answer. */
    public AuthDTO me(Authentication authentication) {
        Map<String, Object> claims = authentication.getAttributes();
        UUID oid = parseOid(claims.get(BlackholeRolesFinder.OBJECT_ID_CLAIM));
        if (oid == null) {
            return new AuthDTO.Error("the token carries no valid object id", ErrorKind.UNAUTHORIZED);
        }
        String name = claims.get("name") instanceof String text && !text.isBlank() ? text : null;
        List<String> roles = authentication.getRoles().stream().sorted(Comparator.naturalOrder()).toList();
        return new AuthDTO.Response(oid, name, roles);
    }

    private static UUID parseOid(Object claim) {
        try {
            return claim instanceof String text ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
