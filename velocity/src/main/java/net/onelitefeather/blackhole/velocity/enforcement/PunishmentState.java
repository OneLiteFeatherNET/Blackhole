package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerFacts;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Answers the enforcement questions of the proxy for a player: is there a ban that denies the login,
 * a mute that denies chat, a server ban that denies a connect. The player's state is read from the
 * backend profile and kept in the {@link PunishmentMirror} so later checks need no network call - but
 * only while the cache is {@linkplain CacheTrust trusted} (the event bus is connected) and the entry is
 * younger than the maximum age; otherwise every check reads the backend again. A cache is never a
 * reason to allow anything: if the backend cannot answer either, {@link Unavailable} is thrown and the
 * callers apply their existing backend-unreachable rule.
 */
public final class PunishmentState {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentState.class);

    /** Whether events keep the mirror current: true only while the event bus is connected. */
    @FunctionalInterface
    public interface CacheTrust {
        boolean trusted();
    }

    /** Reads the backend profile; empty if the owner has none. */
    public interface ProfileReader {
        Optional<PunishProfileDTO> read(String owner) throws Exception;
    }

    /** The backend could not tell what is active for the player. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SectionRegistry sections;
    private final PunishmentMirror mirror;
    private final ProfileReader profiles;
    private final Clock clock;
    private final CacheTrust trust;
    private final Duration maxAge;

    public PunishmentState(SectionRegistry sections, PunishmentMirror mirror, ProfileReader profiles, Clock clock,
                           CacheTrust trust, Duration maxAge) {
        this.sections = sections;
        this.mirror = mirror;
        this.profiles = profiles;
        this.clock = clock;
        this.trust = trust;
        this.maxAge = maxAge;
    }

    /**
     * Reads the player's state afresh (at login) and returns the active punishment in a BAN section.
     *
     * @throws Unavailable if the backend did not answer
     */
    public Optional<PunishmentSyncMessage> onLogin(String owner) {
        load(owner);
        return Enforcement.loginBan(this.mirror.active(owner), this.sections, this.clock.millis());
    }

    /**
     * @return the active punishment in a MUTE section; the state is loaded once if it is not known yet
     * @throws Unavailable if the state is not known (or not trusted) and the backend did not answer
     */
    public Optional<PunishmentSyncMessage> activeMute(String owner) {
        ensureLoaded(owner);
        return Enforcement.mute(this.mirror.active(owner), this.sections, this.clock.millis());
    }

    /**
     * @param facts what CloudNet knows about the server, empty if nothing
     * @return the active punishment that denies connecting to {@code serverName}; empty if the state
     * cannot be determined, so an outage never locks players out of servers
     */
    public Optional<PunishmentSyncMessage> activeServerBan(String owner, String serverName, Optional<ServerFacts> facts) {
        try {
            ensureLoaded(owner);
        } catch (Unavailable e) {
            LOGGER.debug("Server ban state of {} unknown, allowing the connect: {}", owner, e.getMessage());
            return Optional.empty();
        }
        return Enforcement.serverBan(this.mirror.active(owner), this.sections, serverName, facts, this.clock.millis());
    }

    private void ensureLoaded(String owner) {
        if (!this.trust.trusted() || !this.mirror.isFresh(owner, this.maxAge)) {
            load(owner);
        }
    }

    private void load(String owner) {
        if (this.sections.all().isEmpty()) {
            // Started while the backend was unreachable: try again now rather than enforcing nothing.
            this.sections.reload();
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
