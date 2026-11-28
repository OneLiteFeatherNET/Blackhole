package net.onelitefeather.blackhole.discord.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.discord.audit.WebhookTransport.Answer;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Posts one audit message and says what became of it. A {@code 429} waits the server's {@code retry_after} and tries
 * again; a {@code 5xx} or a network error waits an exponential backoff (capped at five minutes); any other {@code 4xx}
 * is final. Logs carry the status and the audit id only, never the webhook URL.
 */
public final class AuditWebhookPoster {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditWebhookPoster.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What became of one message. */
    public enum Delivery {
        /** Accepted by Discord. */
        DELIVERED,
        /** Refused for good (a client error other than 429); it can never succeed. */
        REJECTED,
        /** Still failing after the attempts of this call; the caller keeps it for a later try. */
        UNDELIVERED
    }

    static final int MAX_ATTEMPTS = 6;
    static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

    private final WebhookTransport transport;
    private final Sleeper sleeper;
    private final Metrics metrics;

    public AuditWebhookPoster(@NotNull WebhookTransport transport, @NotNull Sleeper sleeper, @NotNull Metrics metrics) {
        this.transport = transport;
        this.sleeper = sleeper;
        this.metrics = metrics;
    }

    /**
     * @param auditId the id of the entry, for the logs
     * @param json    the webhook body
     * @return the outcome; never throws
     */
    public Delivery post(@NotNull String auditId, @NotNull String json) {
        for (int attempt = 1; ; attempt++) {
            Duration wait;
            try {
                Answer answer = this.transport.post(json);
                if (answer.status() / 100 == 2) {
                    this.metrics.increment(Metrics.Counter.AUDIT_POSTED);
                    return Delivery.DELIVERED;
                }
                if (answer.status() == 429) {
                    wait = retryAfter(answer);
                    LOGGER.warn("The audit webhook is rate limited; entry {} waits {} ms", auditId, wait.toMillis());
                } else if (answer.status() >= 500) {
                    wait = backoffFor(attempt);
                    LOGGER.warn("The audit webhook answered HTTP {} for entry {}", answer.status(), auditId);
                } else {
                    LOGGER.warn("The audit webhook refused entry {} with HTTP {}; it is not retried", auditId, answer.status());
                    this.metrics.increment(Metrics.Counter.AUDIT_REJECTED);
                    return Delivery.REJECTED;
                }
            } catch (IOException e) {
                wait = backoffFor(attempt);
                LOGGER.warn("Posting entry {} failed ({}); trying again", auditId, e.getClass().getSimpleName());
            }
            if (attempt >= MAX_ATTEMPTS) {
                LOGGER.warn("Entry {} is not posted yet; it stays in the event stream", auditId);
                return Delivery.UNDELIVERED;
            }
            this.metrics.increment(Metrics.Counter.AUDIT_RETRIED);
            try {
                this.sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Delivery.UNDELIVERED;
            }
        }
    }

    /** The delay before retry {@code attempt + 1}: one second, then doubling, capped at five minutes. */
    static Duration backoffFor(int attempt) {
        long factor = 1L << Math.min(attempt - 1, 30);
        Duration grown = BASE_BACKOFF.multipliedBy(factor);
        return grown.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : grown;
    }

    /** The body's {@code retry_after} (seconds, may be fractional), else the {@code Retry-After} header, else a second. */
    static Duration retryAfter(Answer answer) {
        try {
            JsonNode body = JSON.readTree(answer.body());
            JsonNode value = body == null ? null : body.get("retry_after");
            if (value != null && value.isNumber()) {
                return clamp(Duration.ofMillis(Math.round(value.asDouble() * 1000)));
            }
        } catch (IOException ignored) {
            // Not JSON: the header below is the fallback.
        }
        if (answer.retryAfter() != null) {
            try {
                return clamp(Duration.ofSeconds(Long.parseLong(answer.retryAfter().strip())));
            } catch (NumberFormatException ignored) {
                // Not a number of seconds: the default below.
            }
        }
        return DEFAULT_RETRY_AFTER;
    }

    private static Duration clamp(Duration wait) {
        if (wait.isNegative()) {
            return Duration.ZERO;
        }
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }
}
