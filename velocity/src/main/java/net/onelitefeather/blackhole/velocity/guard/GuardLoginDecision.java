package net.onelitefeather.blackhole.velocity.guard;

import com.google.inject.Inject;
import net.onelitefeather.blackhole.client.model.IntelPolicy;
import net.onelitefeather.blackhole.client.model.LoginDenialReason;
import net.onelitefeather.blackhole.velocity.intel.VpnLoginDecision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides one login from the backend's login check, and when the backend gives no usable answer, from the
 * guard level this proxy last heard. At {@code OFF} and {@code STRICT} such a login is allowed and counted as
 * a fail-open; at {@code LOCKDOWN} a player without the staff node is refused and counted as a fail-closed.
 */
public final class GuardLoginDecision {

    /**
     * @param policy    the verdict; {@link IntelPolicy#DENY} refuses the login
     * @param reason    why a refusal happened; null for a VPN policy refusal and for every admitted login
     * @param expiresAt when an area ban refusal ends, in epoch milliseconds; null for a permanent ban and for every other outcome
     */
    public record Outcome(@NotNull IntelPolicy policy, @Nullable LoginDenialReason reason, @Nullable Long expiresAt) {

        /**
         * @return whether the login must be refused
         */
        public boolean refused() {
            return this.policy == IntelPolicy.DENY;
        }
    }

    private final VpnLoginDecision vpn;
    private final GuardLevelCache levels;
    private final AtomicLong failOpen = new AtomicLong();
    private final AtomicLong failClosed = new AtomicLong();

    @Inject
    public GuardLoginDecision(@NotNull VpnLoginDecision vpn, @NotNull GuardLevelCache levels) {
        this.vpn = vpn;
        this.levels = levels;
    }

    /**
     * @param owner the player's hashed owner
     * @param ip    the player's address as the proxy sees it
     * @param staff whether the player holds the staff permission node
     * @return the outcome of the login
     */
    public @NotNull Outcome decide(@NotNull String owner, @NotNull String ip, boolean staff) {
        VpnLoginDecision.Verdict verdict = this.vpn.verdict(owner, ip);
        if (verdict.answered()) {
            return new Outcome(verdict.policy(), verdict.reason(), verdict.expiresAt());
        }
        if (this.levels.current() == GuardLevel.LOCKDOWN && !staff) {
            this.failClosed.incrementAndGet();
            return new Outcome(IntelPolicy.DENY, LoginDenialReason.GUARD_LOCKDOWN, null);
        }
        this.failOpen.incrementAndGet();
        return new Outcome(IntelPolicy.ALLOW, null, null);
    }

    /**
     * @return how many logins were allowed without a backend answer, since the proxy started
     */
    public long failOpenCount() {
        return this.failOpen.get();
    }

    /**
     * @return how many logins were refused without a backend answer, since the proxy started
     */
    public long failClosedCount() {
        return this.failClosed.get();
    }
}
