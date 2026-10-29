package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.guard.dto.PresenceDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Keeps the staff presence the proxies report and answers whether staff are on the proxy now. A proxy entry stops
 * counting once it is older than the presence ttl, so a proxy that dies stops counting by itself.
 */
@Singleton
public class GuardPresenceService {

    private static final Pattern PROXY_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final int MAX_STAFF_ONLINE = 100_000;

    private final PresenceStore store;
    private final GuardSettings settings;
    private final Clock clock;

    public GuardPresenceService(PresenceStore store, GuardSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    /** Records one proxy's heartbeat. A count above zero also marks staff as seen now. */
    public PresenceDTO report(PresenceDTO.Heartbeat heartbeat) {
        if (heartbeat.proxyId() == null || !PROXY_ID.matcher(heartbeat.proxyId()).matches()) {
            return new PresenceDTO.Error("proxyId must be 1 to 64 letters, digits, dots, dashes or underscores",
                    ErrorKind.VALIDATION);
        }
        if (heartbeat.staffOnline() < 0 || heartbeat.staffOnline() > MAX_STAFF_ONLINE) {
            return new PresenceDTO.Error("staffOnline must be between 0 and 100000", ErrorKind.VALIDATION);
        }
        long now = this.clock.millis();
        this.store.report(heartbeat.proxyId(), heartbeat.staffOnline(), now);
        if (heartbeat.staffOnline() > 0) {
            this.store.markStaffSeen(now);
        }
        return new PresenceDTO.Response(staffPresent());
    }

    /** Whether any fresh proxy entry reports staff online right now. */
    public boolean staffPresent() {
        long now = this.clock.millis();
        long ttl = this.settings.presenceTtl().toMillis();
        return this.store.entries().stream()
                .anyMatch(entry -> entry.staffOnline() > 0 && now - entry.reportedAt() <= ttl);
    }

    /** When staff were last seen on any proxy, or empty if never. */
    public OptionalLong lastStaffSeen() {
        return this.store.lastStaffSeen();
    }
}
