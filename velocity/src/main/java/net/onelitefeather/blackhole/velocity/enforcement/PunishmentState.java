package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Answers the enforcement questions of the proxy for a player: is there a ban that denies the login,
 * a mute that denies chat, a server ban that denies a connect. The player's state is read from Redis
 * (one key per known section), falling back to the backend profile when Redis is unavailable, and kept
 * in the {@link PunishmentMirror} so later checks need no network call.
 */
public final class PunishmentState {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentState.class);

    /** Reads one mirrored key; throws a runtime exception if Redis is not available. */
    public interface KeyReader {
        Optional<PunishmentSyncMessage> read(String section, String owner);
    }

    /** Reads the backend profile; empty if the owner has none. */
    public interface ProfileReader {
        Optional<PunishProfileDTO> read(String owner) throws Exception;
    }

    /** Neither Redis nor the backend could tell what is active for the player. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SectionRegistry sections;
    private final PunishmentMirror mirror;
    private final KeyReader keys;
    private final ProfileReader profiles;
    private final Clock clock;

    public PunishmentState(SectionRegistry sections, PunishmentMirror mirror, KeyReader keys, ProfileReader profiles, Clock clock) {
        this.sections = sections;
        this.mirror = mirror;
        this.keys = keys;
        this.profiles = profiles;
        this.clock = clock;
    }

    /**
     * Reads the player's state afresh (at login) and returns the active punishment in a BAN section.
     *
     * @throws Unavailable if neither Redis nor the backend answered
     */
    public Optional<PunishmentSyncMessage> onLogin(String owner) {
        load(owner);
        return Enforcement.loginBan(this.mirror.active(owner), this.sections, this.clock.millis());
    }

    /**
     * @return the active punishment in a MUTE section; the state is loaded once if it is not known yet
     * @throws Unavailable if the state is unknown and neither Redis nor the backend answered
     */
    public Optional<PunishmentSyncMessage> activeMute(String owner) {
        ensureLoaded(owner);
        return Enforcement.mute(this.mirror.active(owner), this.sections, this.clock.millis());
    }

    /**
     * @return the active punishment that denies connecting to {@code serverName}; empty if the state
     * cannot be determined, so an outage never locks players out of servers
     */
    public Optional<PunishmentSyncMessage> activeServerBan(String owner, String serverName) {
        try {
            ensureLoaded(owner);
        } catch (Unavailable e) {
            LOGGER.debug("Server ban state of {} unknown, allowing the connect: {}", owner, e.getMessage());
            return Optional.empty();
        }
        return Enforcement.serverBan(this.mirror.active(owner), this.sections, serverName, this.clock.millis());
    }

    private void ensureLoaded(String owner) {
        if (!this.mirror.isLoaded(owner)) {
            load(owner);
        }
    }

    private void load(String owner) {
        if (this.sections.all().isEmpty()) {
            // Started while the backend was unreachable: try again now rather than enforcing nothing.
            this.sections.reload();
        }
        try {
            List<PunishmentSyncMessage> active = new ArrayList<>();
            for (SectionInfo section : this.sections.all()) {
                this.keys.read(section.key(), owner).ifPresent(active::add);
            }
            this.mirror.load(owner, active);
            return;
        } catch (RuntimeException e) {
            LOGGER.debug("Redis unavailable for {}, falling back to the backend profile: {}", owner, e.getMessage());
        }
        try {
            List<PunishmentSyncMessage> active = this.profiles.read(owner)
                    .map(profile -> ProfileAdapter.activeMessages(owner, profile, this.sections))
                    .orElseGet(List::of);
            this.mirror.load(owner, active);
        } catch (Exception e) {
            throw new Unavailable("could not determine the punishments of " + owner + ": " + e.getMessage(), e);
        }
    }
}
