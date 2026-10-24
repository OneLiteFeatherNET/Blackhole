package net.onelitefeather.blackhole.velocity.nats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Turns the backend's events into the proxy's reactions, and decides whether the local punishment cache
 * may be trusted.
 *
 * <p>Core subscriptions are at-most-once, so events are only hints: the cache is trustworthy only while
 * the connection is up. On a disconnect the whole cache is discarded and {@link #trusted()} turns false,
 * so {@link PunishmentState} reads the backend over HTTP for every check; when the subscriptions are in
 * place again the cache is discarded once more (it was filled over HTTP while events were missed) before
 * it is trusted. Nothing here ever allows a player because the bus is down: the answer then comes from
 * the backend, and only if that fails too does the existing backend-unreachable rule apply.</p>
 *
 * <p>Events are handled on the given executor so a slow reaction (an HTTP call) never stalls the NATS
 * thread; a handler failure is logged and never propagates.</p>
 */
public final class NatsSyncService implements BusClient.Listener, PunishmentState.CacheTrust {

    private static final Logger LOGGER = LoggerFactory.getLogger(NatsSyncService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A new report, as announced by the backend: hashes only, never a name.
     *
     * @param reportIdentifier the report's identifier
     */
    public record ReportCreated(@NotNull String reportIdentifier, @Nullable String reporterHash,
                                @Nullable String reportedHash, @Nullable String category) {
    }

    /**
     * What the proxy does with each kind of event.
     *
     * @param punishment    a punishment became active or ended: update the cache and enforce
     * @param evictOwner    forget what the cache holds for the owner (their state changed in a way the event does not describe)
     * @param sectionChange the section list changed
     * @param reportCreated a report was filed
     * @param reportFeedback a reporter (their owner hash) has feedback waiting
     */
    public record Reactions(
            @NotNull Consumer<PunishmentSyncMessage> punishment, @NotNull Consumer<String> evictOwner,
            @NotNull Runnable sectionChange, @NotNull Consumer<ReportCreated> reportCreated,
            @NotNull Consumer<String> reportFeedback
    ) {
    }

    private final PunishmentMirror mirror;
    private final Reactions reactions;
    private final Executor executor;
    private volatile boolean connected;

    public NatsSyncService(@NotNull PunishmentMirror mirror, @NotNull Reactions reactions, @NotNull Executor executor) {
        this.mirror = mirror;
        this.reactions = reactions;
        this.executor = executor;
    }

    /** @return whether the cache is kept current by events: true only between {@link #connected()} and {@link #disconnected()} */
    @Override
    public boolean trusted() {
        return this.connected;
    }

    @Override
    public void connected() {
        if (this.connected) {
            // The client may report the same transition twice; the cache is already being kept current.
            return;
        }
        // Whatever was cached while events were missed is not to be trusted: start from an empty cache.
        this.mirror.clear();
        this.connected = true;
        LOGGER.info("Connected to the event bus, local punishment cache enabled");
    }

    @Override
    public void disconnected() {
        boolean was = this.connected;
        this.connected = false;
        this.mirror.clear();
        if (was) {
            LOGGER.warn("Lost the event bus, checking punishments against the backend until it is back");
        }
    }

    @Override
    public void message(@NotNull String subject, byte @NotNull [] body) {
        try {
            this.executor.execute(() -> handle(subject, body));
        } catch (RuntimeException e) {
            LOGGER.error("An event could not be scheduled: {}", e.getMessage());
        }
    }

    private void handle(String subject, byte[] body) {
        try {
            JsonNode payload = payloadOf(body);
            if (payload == null) {
                LOGGER.warn("Ignoring an unreadable event on {}", subject);
                return;
            }
            switch (subject) {
                case Subjects.PUNISHMENT_CREATED, Subjects.PUNISHMENT_UPDATED -> punishment(payload, true);
                case Subjects.PUNISHMENT_EXPIRED, Subjects.PUNISHMENT_REVOKED -> punishment(payload, false);
                case Subjects.APPEAL_RESOLVED -> {
                    // A full lift ends the ban, a reduction is followed by punishment.updated; either way the
                    // owner's state changed, so the cache forgets it and the next check reads the backend.
                    String owner = text(payload, "owner");
                    if (owner != null) {
                        this.reactions.evictOwner().accept(owner);
                    }
                }
                case Subjects.SECTION_CHANGED -> this.reactions.sectionChange().run();
                case Subjects.REPORT_CREATED -> reportCreated(payload);
                case Subjects.REPORT_FEEDBACK -> {
                    String reporter = text(payload, "reporterHash");
                    if (reporter != null) {
                        this.reactions.reportFeedback().accept(reporter);
                    }
                }
                default -> LOGGER.debug("Ignoring an event on {}", subject);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not handle the event on {}: {}", subject, e.getMessage());
        }
    }

    private void punishment(JsonNode payload, boolean set) {
        String owner = text(payload, "owner");
        String section = text(payload, "section");
        if (owner == null || section == null) {
            LOGGER.warn("Ignoring a punishment event without owner or section");
            return;
        }
        PunishmentSyncMessage message = set
                ? new PunishmentSyncMessage(owner, section, text(payload, "enforcement"), PunishmentSyncMessage.STATE_SET,
                text(payload, "punishmentIdentifier"), text(payload, "templateIdentifier"), text(payload, "reason"),
                payload.path("expiresAt").isNumber() ? payload.get("expiresAt").asLong() : null,
                payload.path("automatic").asBoolean(false))
                : new PunishmentSyncMessage(owner, section, text(payload, "enforcement"), PunishmentSyncMessage.STATE_CLEARED,
                text(payload, "punishmentIdentifier"), null, null, null, false);
        this.reactions.punishment().accept(message);
    }

    private void reportCreated(JsonNode payload) {
        String identifier = text(payload, "reportIdentifier");
        if (identifier == null) {
            LOGGER.warn("Ignoring a report event without an identifier");
            return;
        }
        this.reactions.reportCreated().accept(new ReportCreated(identifier, text(payload, "reporterHash"),
                text(payload, "reportedHash"), text(payload, "category")));
    }

    /** The envelope's payload, null if the body is not an event envelope. */
    private static @Nullable JsonNode payloadOf(byte[] body) {
        try {
            JsonNode payload = MAPPER.readTree(body).path("payload");
            return payload.isObject() ? payload : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
