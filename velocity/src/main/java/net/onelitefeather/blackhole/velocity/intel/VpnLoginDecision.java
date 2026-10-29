package net.onelitefeather.blackhole.velocity.intel;

import com.google.inject.Inject;
import net.onelitefeather.blackhole.client.model.IntelPolicy;
import net.onelitefeather.blackhole.client.model.LoginCheckResponse;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asks the backend for the VPN and hosting verdict of one login, and waits no longer than the configured
 * timeout. A timeout, an error or an unknown verdict allows the login and counts a fail-open event: a
 * broken backend must not lock every player out.
 */
public final class VpnLoginDecision {

    private static final Logger LOGGER = LoggerFactory.getLogger(VpnLoginDecision.class);

    private final BackendGateway gateway;
    private final Duration timeout;
    private final AtomicLong failOpen = new AtomicLong();

    @Inject
    public VpnLoginDecision(@NotNull BackendGateway gateway, @NotNull BlackholeConfig config) {
        this(gateway, config.getIntel().getLoginCheckTimeout());
    }

    /**
     * @param timeout how long the login may wait for the verdict
     */
    public VpnLoginDecision(@NotNull BackendGateway gateway, @NotNull Duration timeout) {
        this.gateway = gateway;
        this.timeout = timeout;
    }

    /**
     * @param owner the player's hashed owner
     * @param ip    the player's address as the proxy sees it
     * @return {@link IntelPolicy#DENY} when the login must be refused; {@link IntelPolicy#ALLOW} or
     * {@link IntelPolicy#FLAG} when it may proceed (also when the backend gave no usable answer)
     */
    public @NotNull IntelPolicy decide(@NotNull String owner, @NotNull String ip) {
        CompletableFuture<LoginCheckResponse> answer = this.gateway.loginCheck(owner, ip);
        IntelPolicy verdict;
        try {
            LoginCheckResponse response = answer.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            verdict = response == null ? null : response.getVerdict();
        } catch (TimeoutException e) {
            answer.cancel(true);
            verdict = null;
            LOGGER.debug("Login check timed out after {}, allowing the login", this.timeout);
        } catch (ExecutionException e) {
            verdict = null;
            LOGGER.debug("Login check failed, allowing the login: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            verdict = null;
        }
        if (verdict == null) {
            this.failOpen.incrementAndGet();
            return IntelPolicy.ALLOW;
        }
        return verdict;
    }

    /**
     * @return how many logins were allowed because the check gave no usable answer, since the proxy started
     */
    public long failOpenCount() {
        return this.failOpen.get();
    }

    /**
     * @return how long a login may wait for the verdict
     */
    Duration timeout() {
        return this.timeout;
    }
}
