package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerFacts;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.section.ServerTargets;

import java.util.Collection;
import java.util.Optional;

/**
 * The enforcement decisions, free of any proxy types so they can be tested with plain data: given a
 * player's active punishments and the known sections, what applies at login, in chat and when
 * connecting to a server.
 */
public final class Enforcement {

    private Enforcement() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * How a punishment is enforced: by its section as the proxy knows it now, otherwise by the
     * enforcement the message carries.
     *
     * @return empty if neither is known
     */
    public static Optional<EnforcementKind> kindOf(PunishmentSyncMessage message, SectionRegistry sections) {
        Optional<EnforcementKind> known = sections.find(message.section()).map(SectionInfo::enforcement);
        if (known.isPresent() || message.enforcement() == null) {
            return known;
        }
        try {
            return Optional.of(EnforcementKind.valueOf(message.enforcement()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * @return the active punishment, in any BAN section, that denies the login
     */
    public static Optional<PunishmentSyncMessage> loginBan(Collection<PunishmentSyncMessage> active, SectionRegistry sections, long nowMillis) {
        return first(active, sections, nowMillis, EnforcementKind.BAN);
    }

    /**
     * @return the active punishment, in any MUTE section, that denies chat
     */
    public static Optional<PunishmentSyncMessage> mute(Collection<PunishmentSyncMessage> active, SectionRegistry sections, long nowMillis) {
        return first(active, sections, nowMillis, EnforcementKind.MUTE);
    }

    /**
     * @param facts what CloudNet knows about the server, empty if nothing; CloudNet sections cover nothing then
     * @return the active punishment, in a SERVER section that covers {@code serverName}, that denies
     * connecting to that server
     */
    public static Optional<PunishmentSyncMessage> serverBan(
            Collection<PunishmentSyncMessage> active, SectionRegistry sections, String serverName,
            Optional<ServerFacts> facts, long nowMillis
    ) {
        return active.stream()
                .filter(message -> message.isActiveAt(nowMillis))
                .filter(message -> kindOf(message, sections).filter(kind -> kind == EnforcementKind.SERVER).isPresent())
                .filter(message -> sections.find(message.section())
                        .map(section -> ServerTargets.covers(section, serverName, facts))
                        .orElse(false))
                .findFirst();
    }

    /**
     * Whether a newly announced SERVER punishment covers the server the player is on right now.
     */
    public static boolean coversServer(
            PunishmentSyncMessage message, SectionRegistry sections, String serverName, Optional<ServerFacts> facts, long nowMillis
    ) {
        return serverBan(java.util.List.of(message), sections, serverName, facts, nowMillis).isPresent();
    }

    private static Optional<PunishmentSyncMessage> first(
            Collection<PunishmentSyncMessage> active, SectionRegistry sections, long nowMillis, EnforcementKind kind
    ) {
        return active.stream()
                .filter(message -> message.isActiveAt(nowMillis))
                .filter(message -> kindOf(message, sections).filter(found -> found == kind).isPresent())
                .findFirst();
    }
}
