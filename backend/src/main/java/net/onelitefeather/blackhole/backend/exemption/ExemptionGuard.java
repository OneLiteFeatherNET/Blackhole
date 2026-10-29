package net.onelitefeather.blackhole.backend.exemption;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether a punishment write may proceed against a player who may be exempt. Every write path (manual, template,
 * kick, warning) calls it before it writes anything. A refusal is an answer, never an exception.
 */
@Singleton
public class ExemptionGuard {

    static final String OVERRIDE_EVENT = "punishment.exemption_overridden";
    /** The key the override is stored under in a punishment's metadata. */
    public static final String OVERRIDE_METADATA_KEY = "exemptionOverride";

    private final ExemptionService exemptions;
    private final StaffLinkService staffLinks;
    private final DomainEventPublisher events;

    public ExemptionGuard(ExemptionService exemptions, StaffLinkService staffLinks, DomainEventPublisher events) {
        this.exemptions = exemptions;
        this.staffLinks = staffLinks;
        this.events = events;
    }

    /**
     * Checks one write.
     *
     * <p>The write is refused with {@code TARGET_EXEMPT} when the target is exempt and the actor's rank does not outrank the
     * exemption. An ADMIN in the panel may pass with a justification; the override is then returned for the punishment's metadata.
     * An override is refused with 400 on a write made for an in-game player, and with 403 for a caller that is not an ADMIN.</p>
     *
     * @param owner      the hashed owner the write targets
     * @param sectionKey the section of the write, or null when it has none
     * @param attribution the attribution of the write ({@code source} is the in-game UUID for a proxy-delegated write)
     * @param actor      the verified caller; null for a system write, which has no rank
     * @param override   the admin's justification, or null
     * @return {@link Outcome.Allowed}, or {@link Outcome.Refused} with the error to answer
     */
    public Outcome check(String owner, @Nullable String sectionKey, Attribution attribution, @Nullable Actor actor,
                         @Nullable ExemptionDTO.Override override) {
        if (override != null) {
            Optional<String> problem = justificationProblem(override.justification());
            if (problem.isPresent()) {
                return refused(ErrorKind.VALIDATION, problem.get());
            }
            String reason = override.justification().strip();
            boolean ownName = attribution.source().equals(attribution.recordedByOid());
            if (!ownName) {
                return refused(ErrorKind.VALIDATION, "an exemption cannot be overridden on a write made for an in-game player");
            }
            if (actor == null || actor.kind() != ActorKind.HUMAN || !actor.admin()) {
                return refused(ErrorKind.FORBIDDEN, "only an admin may override an exemption");
            }
        }
        Optional<ExemptionService.Protection> protection = this.exemptions.protectionOf(owner, sectionKey);
        if (protection.isEmpty()) {
            return new Outcome.Allowed(Map.of());
        }
        Optional<ExemptionRank> actorRank = actorRank(attribution, actor);
        if (actorRank.isPresent() && actorRank.get().outranks(protection.get().rank())) {
            return new Outcome.Allowed(Map.of());
        }
        if (override != null) {
            Map<String, Object> record = new HashMap<>();
            record.put("justification", override.justification().strip());
            record.put("targetRank", protection.get().rank().name());
            record.put("source", String.join(",", protection.get().sources()));
            record.put("adminOid", actor.oid().toString());
            return new Outcome.Allowed(record);
        }
        String message = actorRank.isEmpty()
                ? "the actor is not linked to a staff account, so an exempt player cannot be punished"
                : "the player is exempt at rank " + protection.get().rank() + " and the actor's rank " + actorRank.get()
                + " does not outrank it";
        return refused(ErrorKind.TARGET_EXEMPT, message);
    }

    /**
     * Why a justification may not be stored, or empty when it may. A justification is staff free text kept on the record, so
     * like staff notes and public reasons it is refused (not redacted) when it names a player UUID or an IP address.
     */
    public static Optional<String> justificationProblem(@Nullable String justification) {
        String reason = justification == null ? "" : justification.strip();
        if (reason.isEmpty() || reason.length() > 500) {
            return Optional.of("a justification of 1 to 500 characters is required");
        }
        if (PiiGuard.containsUuid(reason) || PiiGuard.containsIp(reason)) {
            return Optional.of("a justification must not contain a player UUID or an IP address");
        }
        return Optional.empty();
    }

    /**
     * Publishes {@code punishment.exemption_overridden} after the write has been stored. The justification is not published
     * and a failed publish does not fail the write.
     */
    public void announceOverride(String owner, @Nullable String sectionKey, Map<String, Object> record) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner", owner);
        if (sectionKey != null) {
            payload.put("section", sectionKey);
        }
        payload.put("oid", record.get("adminOid"));
        payload.put("targetRank", record.get("targetRank"));
        try {
            this.events.publish(OVERRIDE_EVENT, payload);
        } catch (RuntimeException failed) {
            // The write is already stored; the event is informational.
        }
    }

    /**
     * The rank of the actor. A web caller ranks by its token (ADMIN, otherwise MODERATOR, since every route that writes a
     * punishment needs MODERATOR). An in-game actor ranks by the last recorded role of the staff member it is linked to; an
     * unlinked actor, and an app-only caller, has no rank.
     */
    private Optional<ExemptionRank> actorRank(Attribution attribution, @Nullable Actor actor) {
        if (attribution.source().equals(attribution.recordedByOid())) {
            if (actor == null || actor.kind() != ActorKind.HUMAN) {
                return Optional.empty();
            }
            return Optional.of(actor.admin() ? ExemptionRank.ADMIN : ExemptionRank.MODERATOR);
        }
        return this.staffLinks.linkedOid(attribution.source()).map(this.exemptions::staffRank);
    }

    private static Outcome.Refused refused(ErrorKind kind, String message) {
        return new Outcome.Refused(kind, message);
    }

    /** The result of {@link #check}. */
    public sealed interface Outcome {

        /**
         * The write may proceed.
         *
         * @param record the override record for the punishment's metadata; empty when the write was not an override
         */
        record Allowed(Map<String, Object> record) implements Outcome {

            /** Whether this write passed only because of an admin's override. */
            public boolean overridden() {
                return !this.record.isEmpty();
            }
        }

        record Refused(ErrorKind kind, String message) implements Outcome {
        }
    }
}
