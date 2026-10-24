package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.network.ProtocolVersion;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueProtocol;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueReportRequest;
import net.onelitefeather.blackhole.velocity.cloudnet.CatalogueReporter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Keeps the backend's client rule catalogue of this proxy fresh for the rule editor: a full snapshot at
 * start, one coalesced push (trailing edge, {@code debounce} after the first change) when a new brand or
 * channel shows up, and a keep-alive push every {@code heartbeat}. A failed push is only logged: the next
 * change or heartbeat sends a full snapshot again, and enforcement never depends on it.
 *
 * <p>It holds aggregates only: brands and channels shown by enough distinct players ({@link TraitTally}),
 * the command words of the proxy's command tree (what exists, never what players typed) and the protocol
 * versions the proxy supports.</p>
 */
public final class ClientRuleCatalogueReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientRuleCatalogueReporter.class);

    private final TraitTally tally;
    private final Supplier<Set<String>> commands;
    private final ClientRuleBackend backend;
    private final CatalogueReporter.Timer timer;
    private final String proxyId;
    private final int minPlayers;
    private final Duration debounce;
    private final Duration heartbeat;
    private boolean pending;

    /**
     * @param commands  the literal command words of the proxy's command tree
     * @param proxyId   the id the backend files the snapshot under
     * @param debounce  how long after the first change the coalesced push goes out
     * @param heartbeat the keep-alive period
     */
    public ClientRuleCatalogueReporter(@NotNull TraitTally tally, @NotNull Supplier<Set<String>> commands, @NotNull ClientRuleBackend backend,
                                       @NotNull CatalogueReporter.Timer timer, @NotNull String proxyId, int minPlayers,
                                       @NotNull Duration debounce, @NotNull Duration heartbeat) {
        this.tally = tally;
        this.commands = commands;
        this.backend = backend;
        this.timer = timer;
        this.proxyId = proxyId;
        this.minPlayers = minPlayers;
        this.debounce = debounce;
        this.heartbeat = heartbeat;
    }

    /** Pushes once now, then on changes and heartbeats. */
    public void start() {
        this.timer.every(this.heartbeat, this::push);
        this.timer.after(Duration.ZERO, this::push);
    }

    /** Something worth listing may have changed; the push is coalesced. */
    public void changed() {
        synchronized (this) {
            if (this.pending) {
                return;
            }
            this.pending = true;
        }
        this.timer.after(this.debounce, () -> {
            synchronized (this) {
                this.pending = false;
            }
            push();
        });
    }

    void push() {
        try {
            TraitTally.Snapshot snapshot = this.tally.snapshot(this.minPlayers);
            this.backend.reportCatalogue(new ClientRuleCatalogueReportRequest().proxyId(this.proxyId).brands(snapshot.brands())
                    .channels(snapshot.channels()).commands(List.copyOf(this.commands.get())).protocols(supportedProtocols()));
        } catch (ApiException | RuntimeException e) {
            LOGGER.warn("Could not report the client rule catalogue to the backend, will retry: {}", e.getMessage());
        }
    }

    /** The protocol versions this proxy can talk to, one entry per protocol number, named by the Minecraft versions it covers. */
    static List<ClientRuleCatalogueProtocol> supportedProtocols() {
        Map<Integer, String> byProtocol = new TreeMap<>();
        for (ProtocolVersion version : ProtocolVersion.SUPPORTED_VERSIONS) {
            if (version.getProtocol() >= 0) {
                byProtocol.putIfAbsent(version.getProtocol(), name(version));
            }
        }
        List<ClientRuleCatalogueProtocol> protocols = new ArrayList<>();
        byProtocol.forEach((protocol, name) -> protocols.add(new ClientRuleCatalogueProtocol().version(protocol).name(name)));
        return protocols;
    }

    private static String name(ProtocolVersion version) {
        String first = version.getVersionIntroducedIn();
        String last = version.getMostRecentSupportedVersion();
        String name = first.equals(last) ? first : first + "-" + last;
        return name.length() <= 32 ? name : name.substring(0, 32);
    }
}
