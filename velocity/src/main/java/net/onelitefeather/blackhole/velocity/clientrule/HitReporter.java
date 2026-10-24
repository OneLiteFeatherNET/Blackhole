package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientRuleHitCreateRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * Reports a rule hit to the backend and announces it to the team, off the event thread. Both are allowed
 * to fail on their own: without the backend the hit is not recorded (and a TEMPLATE applies nothing), and
 * without Redis the announcing proxy tells its own staff; the notice never depends on the backend.
 */
public final class HitReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HitReporter.class);

    private final ClientRuleBackend backend;
    private final ClientRuleNotifier notifier;
    private final Executor async;

    /**
     * @param async runs the backend call and the announcement off the event thread
     */
    public HitReporter(@NotNull ClientRuleBackend backend, @NotNull ClientRuleNotifier notifier, @NotNull Executor async) {
        this.backend = backend;
        this.notifier = notifier;
        this.async = async;
    }

    /**
     * @param playerName the player's name, for the staff notice only; it is never sent to the backend
     * @param owner      the hashed owner
     * @param server     the backend server the player was on or connecting to, null if none
     * @param enforced   whether the proxy refused the connection or the command
     * @param bypassed   whether the player holds the bypass permission
     */
    public void report(@NotNull String playerName, @NotNull String owner, @Nullable String server, @NotNull ClientRule rule, boolean enforced,
                       boolean bypassed) {
        this.async.execute(() -> {
            try {
                this.backend.reportHit(new ClientRuleHitCreateRequest().rule(rule.key()).owner(owner).server(server).enforced(enforced).bypassed(bypassed));
            } catch (ApiException | RuntimeException e) {
                // Fail open: the hit is not recorded and a TEMPLATE applies nothing; the notice below still happens.
                LOGGER.debug("Client rule hit {} not recorded: {}", rule.key(), e.getMessage());
            }
            try {
                this.notifier.announce(new ClientRuleNotice(rule.displayName(), playerName, rule.kind().getValue(), rule.action().getValue(), enforced));
            } catch (RuntimeException e) {
                LOGGER.debug("Client rule notice {} not delivered: {}", rule.key(), e.getMessage());
            }
        });
    }
}
