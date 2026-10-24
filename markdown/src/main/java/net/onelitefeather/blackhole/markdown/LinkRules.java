package net.onelitefeather.blackhole.markdown;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether a link target may become a {@link Node.Link} and whether it points to a trusted domain. Nothing is fetched or
 * resolved: this is string and {@link URI} work only.
 */
public final class LinkRules {

    /** The longest accepted target, in characters. */
    public static final int MAX_TARGET = 2048;

    /** The domain that is trusted when nothing else is configured. */
    public static final String DEFAULT_TRUSTED_DOMAIN = "onelitefeather.net";

    /**
     * An accepted target.
     *
     * @param href     the normalised absolute URL
     * @param external {@code false} only for a trusted domain or a true subdomain of one
     */
    public record Target(String href, boolean external) {
    }

    private final List<String> trusted;

    /**
     * @param trustedDomains the domains whose links are not external; a host is trusted when it equals one of them or ends with a dot
     *                       followed by one of them. Blank entries are ignored; an empty collection makes every link external.
     */
    public LinkRules(Collection<String> trustedDomains) {
        List<String> domains = new ArrayList<>();
        for (String domain : trustedDomains) {
            String normalised = domain == null ? "" : stripDot(domain.strip().toLowerCase(Locale.ROOT));
            if (!normalised.isEmpty()) {
                domains.add(normalised);
            }
        }
        this.trusted = List.copyOf(domains);
    }

    /** The default rules: {@value #DEFAULT_TRUSTED_DOMAIN} is trusted. */
    public static LinkRules defaults() {
        return new LinkRules(List.of(DEFAULT_TRUSTED_DOMAIN));
    }

    /**
     * Checks a target. It is accepted only when, after trimming, it has at most {@value #MAX_TARGET} characters, no control character,
     * no non-ASCII character, no space, backslash, {@code <}, {@code >} or {@code "}, parses as a {@link URI} with the scheme {@code http}
     * or {@code https} (any case), a non-empty host, no user info and a port from 1 to 65535.
     *
     * @return the normalised target, or empty when any rule fails
     */
    public Optional<Target> accept(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String target = raw.strip();
        if (target.isEmpty() || target.length() > MAX_TARGET) {
            return Optional.empty();
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\' || c == '<' || c == '>' || c == '"') {
                return Optional.empty();
            }
        }
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.isOpaque()) {
            return Optional.empty();
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = stripDot(uri.getHost().toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (host.isEmpty() || port == 0 || port > 65535 || port < -1) {
            return Optional.empty();
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        StringBuilder href = new StringBuilder(scheme.toLowerCase(Locale.ROOT)).append("://").append(host);
        if (port != -1) {
            href.append(':').append(port);
        }
        href.append(path);
        if (uri.getRawQuery() != null) {
            href.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            href.append('#').append(uri.getRawFragment());
        }
        return Optional.of(new Target(href.toString(), !trusted(host)));
    }

    private boolean trusted(String host) {
        for (String domain : this.trusted) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private static String stripDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
