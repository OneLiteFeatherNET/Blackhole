package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Value;
import io.micronaut.rabbitmq.annotation.Queue;
import io.micronaut.rabbitmq.annotation.RabbitListener;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.RabbitTopology;
import net.onelitefeather.blackhole.backend.dto.EloReasonCode;
import net.onelitefeather.blackhole.backend.dto.EloTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies a gameplay-ELO delta for every external signal (Phase 4's {@code POST /signal}) -
 * this is the generic connector framework's actual integration point with ELO. Blackhole never
 * interprets {@code signalType} itself; a connector may optionally hint at severity via a
 * {@code severity} or {@code confidence} field in the signal payload (0.0-1.0, scaling the
 * default delta), otherwise the full default delta applies.
 *
 * <p>Uses the simple declarative {@code @RabbitListener}/{@code @Queue} consumer (auto-ack, no
 * dead-letter retry) rather than {@code WebhookDispatchConsumer}'s raw-channel approach - a
 * transient failure to score one signal is logged and skipped, not safety-critical the way a
 * webhook delivery guarantee is.</p>
 */
@RabbitListener
public class EloSignalConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(EloSignalConsumer.class);

    private final EloService eloService;
    private final int defaultDelta;

    public EloSignalConsumer(EloService eloService, @Value("${blackhole.elo.signal.default-delta:-150}") int defaultDelta) {
        this.eloService = eloService;
        this.defaultDelta = defaultDelta;
    }

    @Queue(RabbitTopology.ELO_SIGNAL_QUEUE)
    void receive(DomainEvent event) {
        try {
            Object ownerRaw = event.payload().get("owner");
            if (ownerRaw == null) {
                LOGGER.warn("Signal event {} is missing owner, skipping ELO update", event.eventType());
                return;
            }

            String owner = ownerRaw.toString();
            int delta = resolveDelta(event.payload());

            Map<String, Object> metaData = new HashMap<>();
            metaData.put("signalType", event.eventType());
            this.eloService.applyDelta(owner, EloTrack.GAMEPLAY, delta, EloReasonCode.ANTICHEAT_FLAG, null, metaData);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to apply gameplay-ELO delta for signal event {}: {}", event.eventType(), e.getMessage());
        }
    }

    private int resolveDelta(Map<String, Object> payload) {
        Double hint = numericHint(payload.get("severity"));
        if (hint == null) {
            hint = numericHint(payload.get("confidence"));
        }
        if (hint == null) {
            return this.defaultDelta;
        }
        double clamped = Math.max(0.0, Math.min(1.0, hint));
        return (int) Math.round(this.defaultDelta * clamped);
    }

    private static Double numericHint(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
