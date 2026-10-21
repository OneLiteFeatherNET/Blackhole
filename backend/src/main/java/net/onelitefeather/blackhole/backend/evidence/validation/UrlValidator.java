package net.onelitefeather.blackhole.backend.evidence.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Checks the shape of the reference URL of a REPLAY or LINK. References are stored and shown to
 * staff, never requested: this class only parses text and has no network access of any kind - it
 * must stay that way, because the backend deliberately has no SSRF surface for caller-supplied
 * URLs (a server-side fetch would need hardening first).
 */
public final class UrlValidator {

    /** The longest reference accepted, in characters. */
    public static final int MAX_LENGTH = 2048;

    /**
     * @return why the reference is not acceptable, empty if it is an absolute http(s) URL of at most
     * {@value #MAX_LENGTH} characters with a host and without user info
     */
    public static Optional<String> check(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.of("reference is required");
        }
        if (reference.length() > MAX_LENGTH) {
            return Optional.of("reference must be at most " + MAX_LENGTH + " characters");
        }
        if (reference.chars().anyMatch(c -> c <= 0x20 || c == 0x7F)) {
            return Optional.of("reference must not contain whitespace or control characters");
        }
        URI uri;
        try {
            uri = new URI(reference);
        } catch (URISyntaxException e) {
            return Optional.of("reference is not a valid URL");
        }
        if (!uri.isAbsolute()) {
            return Optional.of("reference must be an absolute URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of("reference must use http or https");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("reference must not contain user info");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Optional.of("reference must have a host");
        }
        return Optional.empty();
    }

    private UrlValidator() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
