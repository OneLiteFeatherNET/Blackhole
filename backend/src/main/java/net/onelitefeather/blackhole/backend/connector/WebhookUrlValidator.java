package net.onelitefeather.blackhole.backend.connector;

import net.onelitefeather.blackhole.backend.connector.controller.ConnectorController;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF hardening for connector-supplied {@code deliveryUrl}s. An {@code ADMIN} caller - a
 * genuinely less-trusted party than the platform operator - can otherwise point the backend's own
 * outbound webhook requests at internal-only infrastructure reachable from the backend host.
 *
 * <p>Must be called both when a subscription is created ({@code ConnectorController}) and again
 * immediately before every actual delivery ({@code WebhookDispatchConsumer}) - re-checking at
 * delivery time guards against DNS rebinding (a hostname resolving to a public address at
 * creation time, then to an internal one by the time it's actually dispatched).</p>
 */
@Singleton
public class WebhookUrlValidator {

    private final boolean allowPrivateNetworks;

    public WebhookUrlValidator(@Value("${blackhole.webhook.allow-private-networks:false}") boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    /**
     * @throws InvalidWebhookUrlException if the URL is malformed, uses an unsupported scheme,
     *                                     carries embedded credentials, or (unless private
     *                                     networks are explicitly allowed) resolves to a
     *                                     loopback/link-local/private/multicast/CGNAT address
     */
    public void validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidWebhookUrlException("Malformed deliveryUrl: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new InvalidWebhookUrlException("deliveryUrl must use http or https");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidWebhookUrlException("deliveryUrl must not contain embedded credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookUrlException("deliveryUrl must have a host");
        }

        if (this.allowPrivateNetworks) {
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new InvalidWebhookUrlException("Could not resolve deliveryUrl host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isDisallowed(address)) {
                throw new InvalidWebhookUrlException(
                        "deliveryUrl resolves to a disallowed internal/private address (" + address.getHostAddress() + ")"
                );
            }
        }
    }

    private static boolean isDisallowed(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // Carrier-grade NAT: 100.64.0.0/10
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && (second & 0xC0) == 64;
        }
        if (bytes.length == 16) {
            // IPv6 unique local addresses: fc00::/7
            int first = bytes[0] & 0xFF;
            return (first & 0xFE) == 0xFC;
        }
        return false;
    }
}
