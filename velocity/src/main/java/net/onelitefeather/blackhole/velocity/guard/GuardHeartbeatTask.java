package net.onelitefeather.blackhole.velocity.guard;

import com.velocitypowered.api.permission.PermissionSubject;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.GuardPresenceResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tells the backend how many staff are online on this proxy and takes back the level in force. A failed
 * beat keeps the cached level and is only counted: it never affects a login.
 */
public final class GuardHeartbeatTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardHeartbeatTask.class);

    private final GuardBackend backend;
    private final GuardLevelCache levels;
    private final String proxyId;
    private final AtomicLong failures = new AtomicLong();

    public GuardHeartbeatTask(@NotNull GuardBackend backend, @NotNull GuardLevelCache levels, @NotNull String proxyId) {
        this.backend = backend;
        this.levels = levels;
        this.proxyId = proxyId;
    }

    /**
     * Sends one heartbeat. Blocking; the caller runs it off the server thread.
     *
     * @param online the players online on this proxy right now
     */
    public void beat(@NotNull Collection<? extends PermissionSubject> online) {
        int staff = GuardHeartbeatCounter.staffOnline(online);
        try {
            GuardPresenceResponse answer = this.backend.presence(this.proxyId, staff);
            GuardLevel level = GuardLevel.parse(answer.getEffectiveLevel());
            if (level == null) {
                this.failures.incrementAndGet();
                LOGGER.warn("The guard heartbeat answered an unknown level, keeping the cached level");
                return;
            }
            this.levels.update(level);
        } catch (ApiException e) {
            this.failures.incrementAndGet();
            LOGGER.warn("Guard heartbeat failed, keeping the cached level and allowing logins: {}", e.getMessage());
        }
    }

    /**
     * @return how many beats failed since the proxy started
     */
    public long failures() {
        return this.failures.get();
    }
}
