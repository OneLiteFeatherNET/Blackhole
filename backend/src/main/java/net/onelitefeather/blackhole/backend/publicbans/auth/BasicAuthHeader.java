package net.onelitefeather.blackhole.backend.publicbans.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * An {@code Authorization} header read as HTTP Basic (RFC 7617). The password keeps every colon after the first one.
 *
 * @param kind     {@link Kind#MISSING} without a header, {@link Kind#MALFORMED} for another scheme, bad base64 or no colon,
 *                 {@link Kind#VALID} otherwise
 * @param user     the user part, or {@code null} unless valid
 * @param password the password part, or {@code null} unless valid
 */
public record BasicAuthHeader(Kind kind, String user, String password) {

    public enum Kind {
        MISSING, MALFORMED, VALID
    }

    public static BasicAuthHeader parse(String header) {
        if (header == null || header.isBlank()) {
            return new BasicAuthHeader(Kind.MISSING, null, null);
        }
        String text = header.strip();
        int space = text.indexOf(' ');
        if (space < 0 || !"basic".equalsIgnoreCase(text.substring(0, space))) {
            return malformed();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(text.substring(space + 1).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return malformed();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return malformed();
        }
        return new BasicAuthHeader(Kind.VALID, decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    private static BasicAuthHeader malformed() {
        return new BasicAuthHeader(Kind.MALFORMED, null, null);
    }
}
