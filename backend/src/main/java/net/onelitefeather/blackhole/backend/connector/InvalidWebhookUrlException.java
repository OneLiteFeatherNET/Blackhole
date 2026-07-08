package net.onelitefeather.blackhole.backend.connector;

/**
 * Thrown when a connector-supplied {@code deliveryUrl} fails SSRF-hardening validation - either
 * at subscription creation time ({@code ConnectorController}) or, again, at actual dispatch time
 * ({@code WebhookDispatchConsumer}, which guards against DNS rebinding between the two).
 */
public class InvalidWebhookUrlException extends RuntimeException {

    public InvalidWebhookUrlException(String message) {
        super(message);
    }
}
