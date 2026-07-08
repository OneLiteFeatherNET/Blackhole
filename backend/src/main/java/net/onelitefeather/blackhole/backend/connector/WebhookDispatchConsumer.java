package net.onelitefeather.blackhole.backend.connector;

import com.rabbitmq.client.Channel;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.rabbitmq.connect.ChannelPool;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.RabbitTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fans out every domain event to whichever {@code EventSubscriptionEntity} rows are subscribed
 * to its type, signing each outbound delivery with the subscription's own secret. Delivery
 * failures are retried via a native RabbitMQ TTL+dead-letter-exchange loop (see
 * {@link BlackholeRabbitTopology}) rather than an in-process retry loop, so retries survive a
 * backend restart.
 *
 * <p>Uses the raw RabbitMQ Java client (not {@code @RabbitListener}) because this needs manual
 * ack/nack to drive the dead-letter retry, and needs to read the {@code x-death} header RabbitMQ
 * attaches on redelivery to know how many attempts have already been made.</p>
 *
 * <p><b>Delivery semantics:</b> a single domain event fans out to every matching subscription in
 * one pass; if any one delivery fails, the whole message is retried, which may re-deliver to
 * subscriptions that already succeeded. This is the same at-least-once, receiver-deduplicates
 * convention most webhook providers use (Stripe, GitHub, ...) - simpler than tracking per-
 * subscription delivery state, and not something the roadmap asked for.</p>
 */
@Singleton
public class WebhookDispatchConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDispatchConsumer.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ChannelPool channelPool;
    private final EventSubscriptionRepository subscriptionRepository;
    private final JsonMapper jsonMapper;
    private final WebhookUrlValidator webhookUrlValidator;
    private final HttpClient httpClient;
    private final int maxRetries;

    public WebhookDispatchConsumer(
            @Named("default") ChannelPool channelPool,
            EventSubscriptionRepository subscriptionRepository,
            JsonMapper jsonMapper,
            WebhookUrlValidator webhookUrlValidator,
            @Value("${blackhole.webhook.max-retries:5}") int maxRetries
    ) {
        this.channelPool = channelPool;
        this.subscriptionRepository = subscriptionRepository;
        this.jsonMapper = jsonMapper;
        this.webhookUrlValidator = webhookUrlValidator;
        this.maxRetries = maxRetries;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Explicit, not just relying on the JDK default: a redirect to an
                // internal/private address would otherwise bypass WebhookUrlValidator entirely,
                // since only the original deliveryUrl is validated.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @EventListener
    void onStartup(ApplicationStartupEvent event) {
        try {
            Channel channel = this.channelPool.getChannel();
            channel.basicConsume(RabbitTopology.WEBHOOK_DISPATCH_QUEUE, false, (consumerTag, delivery) -> {
                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                try {
                    boolean allSucceeded = dispatch(delivery.getBody());
                    if (allSucceeded) {
                        channel.basicAck(deliveryTag, false);
                    } else {
                        retryOrPark(channel, delivery.getProperties().getHeaders(), delivery.getBody(), deliveryTag);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to process webhook dispatch: {}", e.getMessage());
                    try {
                        retryOrPark(channel, delivery.getProperties().getHeaders(), delivery.getBody(), deliveryTag);
                    } catch (IOException ioException) {
                        LOGGER.error("Failed to retry/park webhook dispatch after processing error: {}", ioException.getMessage());
                    }
                }
            }, consumerTag -> {
                // no-op cancel callback
            });
            LOGGER.info("Listening for domain events to dispatch to connector webhooks");
        } catch (IOException e) {
            LOGGER.error("Failed to set up webhook dispatch consumer: {}", e.getMessage());
        }
    }

    /**
     * @return {@code true} if the message does not need to be retried - every matching
     * subscription either succeeded or was permanently rejected (e.g. an SSRF-blocked URL,
     * which retrying would never fix); {@code false} if at least one delivery transiently
     * failed and the whole message should be redelivered
     */
    private boolean dispatch(byte[] body) throws IOException {
        DomainEvent event = this.jsonMapper.readValue(body, DomainEvent.class);
        List<EventSubscriptionEntity> subscriptions = this.subscriptionRepository.findByActiveTrue()
                .stream()
                .filter(subscription -> subscription.getEventTypes().contains(event.eventType()))
                .toList();

        if (subscriptions.isEmpty()) {
            return true;
        }

        boolean needsRetry = false;
        for (EventSubscriptionEntity subscription : subscriptions) {
            if (deliver(subscription, body) == DeliveryOutcome.TRANSIENT_FAILURE) {
                needsRetry = true;
            }
        }
        return !needsRetry;
    }

    private enum DeliveryOutcome {
        SUCCESS,
        TRANSIENT_FAILURE,
        /** e.g. an SSRF-blocked or otherwise malformed deliveryUrl - retrying can't help. */
        REJECTED
    }

    private DeliveryOutcome deliver(EventSubscriptionEntity subscription, byte[] body) {
        long now = System.currentTimeMillis();

        try {
            // Re-validated here, not just at subscription-creation time in ConnectorController,
            // to guard against DNS rebinding: the host could resolve to a public address when
            // the subscription was created and to an internal one by the time it's dispatched.
            this.webhookUrlValidator.validate(subscription.getDeliveryUrl());
        } catch (InvalidWebhookUrlException e) {
            LOGGER.error("Refusing webhook delivery for subscription {}: {}", subscription.getIdentifier(), e.getMessage());
            recordAttempt(subscription, now, false);
            return DeliveryOutcome.REJECTED;
        }

        try {
            String signature = hmacSha256Hex(body, subscription.getSigningSecret());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getDeliveryUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Blackhole-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = this.httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                recordAttempt(subscription, now, true);
                return DeliveryOutcome.SUCCESS;
            }
            LOGGER.warn("Webhook delivery to {} returned status {}", subscription.getDeliveryUrl(), response.statusCode());
        } catch (IOException | InterruptedException | GeneralSecurityException e) {
            LOGGER.warn("Webhook delivery to {} failed: {}", subscription.getDeliveryUrl(), e.getMessage());
        }
        recordAttempt(subscription, now, false);
        return DeliveryOutcome.TRANSIENT_FAILURE;
    }

    private void recordAttempt(EventSubscriptionEntity subscription, long now, boolean success) {
        subscription.setLastAttemptAt(now);
        if (success) {
            subscription.setLastSuccessAt(now);
            subscription.setFailureCount(0);
        } else {
            subscription.setFailureCount(subscription.getFailureCount() + 1);
        }
        this.subscriptionRepository.update(subscription);
    }

    private void retryOrPark(Channel channel, Map<String, Object> headers, byte[] body, long deliveryTag) throws IOException {
        int retryCount = countRetries(headers);
        if (retryCount >= this.maxRetries) {
            LOGGER.error("Webhook dispatch exhausted {} retries, parking in {}", this.maxRetries, RabbitTopology.WEBHOOK_FAILED_QUEUE);
            channel.basicPublish("", RabbitTopology.WEBHOOK_FAILED_QUEUE, null, body);
            channel.basicAck(deliveryTag, false);
        } else {
            channel.basicNack(deliveryTag, false, false);
        }
    }

    @SuppressWarnings("unchecked")
    private int countRetries(Map<String, Object> headers) {
        if (headers == null) {
            return 0;
        }
        Object deaths = headers.get("x-death");
        if (!(deaths instanceof List<?> deathList)) {
            return 0;
        }
        for (Object deathObj : deathList) {
            if (deathObj instanceof Map<?, ?> death) {
                Object queue = death.get("queue");
                Object count = death.get("count");
                if (RabbitTopology.WEBHOOK_RETRY_QUEUE.equals(queueAsString(queue)) && count instanceof Number number) {
                    return number.intValue();
                }
            }
        }
        return 0;
    }

    private static String queueAsString(Object queue) {
        return queue == null ? null : queue.toString();
    }

    private static String hmacSha256Hex(byte[] data, String secret) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] result = mac.doFinal(data);
        StringBuilder hex = new StringBuilder(result.length * 2);
        for (byte b : result) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
