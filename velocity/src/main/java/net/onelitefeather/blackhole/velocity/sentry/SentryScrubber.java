package net.onelitefeather.blackhole.velocity.sentry;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

/**
 * The {@code beforeSend} hook of the Sentry reporter. Ban IDs never leave the proxy: they are removed
 * from every text of an event, and so are credentials (Authorization and cookie headers, bearer tokens,
 * {@code secret=value} pairs and the values registered with {@link #addSecret}, such as the client secret
 * or the NATS credentials path).
 */
public final class SentryScrubber {

    static final String BAN_ID_PLACEHOLDER = "[ban-id]";
    static final String SECRET_PLACEHOLDER = "[redacted]";

    /** A Ban ID as typed or printed: a hash and eight Crockford characters, see the backend's BanIdCodec. */
    private static final Pattern BAN_ID = Pattern.compile("#\\s?[0-9A-Za-z]{8}(?![0-9A-Za-z])");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(client[_-]?secret|password|passwd|secret|token|api[_-]?key)(\"?\\s*[:=]\\s*\"?)[^\\s\"&,;}]+");
    private static final Set<String> CREDENTIAL_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");

    private final Set<String> secrets = new CopyOnWriteArraySet<>();

    /**
     * Registers a value that must never be sent, such as the client secret. Blank values are ignored.
     *
     * @param value the secret text as it would appear in an event
     */
    public void addSecret(String value) {
        if (value != null && !value.isBlank()) {
            secrets.add(value.strip());
        }
    }

    /**
     * Scrubs the event in place and returns it; the hook never drops an event.
     *
     * @param event the event Sentry is about to send
     * @param hint  unused, the hook needs nothing from the SDK's context
     * @return the same event, scrubbed
     */
    public SentryEvent scrub(@NotNull SentryEvent event, Hint hint) {
        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(text(message.getMessage()));
            message.setFormatted(text(message.getFormatted()));
        }
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException exception : exceptions) {
                exception.setValue(text(exception.getValue()));
            }
        }
        List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
        if (breadcrumbs != null) {
            for (Breadcrumb breadcrumb : breadcrumbs) {
                breadcrumb.setMessage(text(breadcrumb.getMessage()));
            }
        }
        if (event.getTags() != null) {
            event.getTags().replaceAll((key, value) -> text(value));
        }
        if (event.getRequest() != null) {
            scrubRequest(event.getRequest());
        }
        return event;
    }

    /**
     * @param input any text, may be null
     * @return the text without Ban IDs, bearer tokens, secret assignments and registered secrets
     */
    String text(String input) {
        if (input == null) {
            return null;
        }
        String result = BAN_ID.matcher(input).replaceAll(BAN_ID_PLACEHOLDER);
        result = BEARER.matcher(result).replaceAll(SECRET_PLACEHOLDER);
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1$2" + SECRET_PLACEHOLDER);
        for (String secret : secrets) {
            result = result.replace(secret, SECRET_PLACEHOLDER);
        }
        return result;
    }

    private void scrubRequest(Request request) {
        request.setUrl(text(request.getUrl()));
        request.setQueryString(text(request.getQueryString()));
        request.setCookies(null);
        if (request.getHeaders() != null) {
            Map<String, String> headers = new HashMap<>();
            request.getHeaders().forEach((name, value) -> {
                if (!CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    headers.put(name, text(value));
                }
            });
            request.setHeaders(headers);
        }
    }
}
