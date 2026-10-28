package net.onelitefeather.blackhole.analytics.mapping;

import java.util.List;
import java.util.Optional;

/**
 * The analytics table each event type is written to. An event type belongs to exactly one family; a type outside every
 * list is not stored (it is dead-lettered by the mapper).
 *
 * <p>{@code typedKeys} are the payload keys that get their own column. Every other payload key, except the owner, is
 * kept in the {@code extra} JSON column.</p>
 */
public enum EventFamily {

    PUNISHMENT("punishment_events",
            List.of("punishment.created", "punishment.updated", "punishment.expired", "punishment.revoked"),
            List.of("punishmentIdentifier", "templateIdentifier", "permanent", "reason", "expiresAt",
                    "automatic", "revokedBy", "verified")),

    PROFILE("profile_events",
            List.of("profile.created", "profile.updated", "profile.deleted"),
            List.of()),

    APPEAL("appeal_events",
            List.of("appeal.submitted", "appeal.resolved"),
            List.of("appealIdentifier", "punishmentIdentifier", "decision", "eligible")),

    REPORT("report_events",
            List.of("report.created", "report.resolved", "report.feedback"),
            List.of("reportIdentifier", "feedbackIdentifier", "reporterHash", "reportedHash",
                    "category", "status", "outcome")),

    ELO("elo_events",
            List.of("elo.threshold_crossed", "section.escalated"),
            List.of("section", "tier", "resultingScore", "punishmentIdentifier")),

    CLIENT_RULE("client_rule_hits",
            List.of("client-rule.hit"),
            List.of("rule", "kind", "action")),

    EVASION("evasion_events",
            List.of("evasion.detected", "evasion.client_match"),
            List.of("token")),

    SECTION("section_events",
            List.of("section.changed"),
            List.of("section"));

    private final String table;
    private final List<String> eventTypes;
    private final List<String> typedKeys;

    EventFamily(String table, List<String> eventTypes, List<String> typedKeys) {
        this.table = table;
        this.eventTypes = eventTypes;
        this.typedKeys = typedKeys;
    }

    public String table() {
        return table;
    }

    public List<String> eventTypes() {
        return eventTypes;
    }

    public List<String> typedKeys() {
        return typedKeys;
    }

    public static Optional<EventFamily> of(String eventType) {
        for (EventFamily family : values()) {
            if (family.eventTypes.contains(eventType)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }
}
