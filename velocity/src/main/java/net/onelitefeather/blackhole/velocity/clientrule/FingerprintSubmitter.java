package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientFingerprintCreateRequest;
import net.onelitefeather.blackhole.velocity.cloudnet.CatalogueReporter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Sends the client traits of a player to the backend for the fingerprint, once the traits have settled:
 * {@code settle} after the first successful server connect (late brand and channel events have arrived by
 * then), and again, after another settle period, only if the trait set changed. The protocol version is not
 * part of it. The backend hashes the traits and stores only the hash; nothing is kept here but a short
 * signature of what was last sent, for the comparison.
 *
 * <p>Fail open: a refused or failed submission only shows up in the debug log. When the backend says
 * fingerprinting is off (503) no further submissions are tried for {@link #PAUSE_AFTER_UNAVAILABLE}.</p>
 */
public final class FingerprintSubmitter {

    private static final Logger LOGGER = LoggerFactory.getLogger(FingerprintSubmitter.class);

    static final Duration PAUSE_AFTER_UNAVAILABLE = Duration.ofMinutes(10);
    /** The backend refuses longer lists; trimming here keeps one odd client from being skipped altogether. */
    static final int MAX_ENTRIES = 256;
    static final int MAX_BRAND = 64;

    private static final class State {
        boolean connected;
        boolean scheduled;
        String lastSent;
    }

    private final TraitCollector traits;
    private final ClientRuleBackend backend;
    private final CatalogueReporter.Timer timer;
    private final Clock clock;
    private final Duration settle;
    private final Executor async;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private volatile Instant pausedUntil = Instant.MIN;

    /**
     * @param settle how long traits get to arrive after the first connect, and between changes and their submission
     * @param async  runs the backend call off the scheduler thread
     */
    public FingerprintSubmitter(@NotNull TraitCollector traits, @NotNull ClientRuleBackend backend, @NotNull CatalogueReporter.Timer timer,
                                @NotNull Clock clock, @NotNull Duration settle, @NotNull Executor async) {
        this.traits = traits;
        this.backend = backend;
        this.timer = timer;
        this.clock = clock;
        this.settle = settle;
        this.async = async;
    }

    /** The player connected to a backend server for the first time (or again): the traits settle from now on. */
    public void connected(@NotNull String owner) {
        State state = this.states.computeIfAbsent(owner, key -> new State());
        synchronized (state) {
            state.connected = true;
        }
        schedule(owner, state);
    }

    /** A trait arrived or changed; it is submitted after the settle period if the player is connected. */
    public void traitsChanged(@NotNull String owner) {
        State state = this.states.get(owner);
        if (state != null) {
            schedule(owner, state);
        }
    }

    /** Forgets the player who left. */
    public void forget(@NotNull String owner) {
        this.states.remove(owner);
    }

    private void schedule(String owner, State state) {
        synchronized (state) {
            if (!state.connected || state.scheduled) {
                return;
            }
            state.scheduled = true;
        }
        this.timer.after(this.settle, () -> this.async.execute(() -> submit(owner, state)));
    }

    private void submit(String owner, State state) {
        synchronized (state) {
            state.scheduled = false;
        }
        ClientTraits known = this.traits.traits(owner).orElse(null);
        if (known == null || this.clock.instant().isBefore(this.pausedUntil)) {
            return;
        }
        List<String> channels = limited(known.channels());
        List<String> mods = limited(known.mods());
        String brand = known.brand() == null ? null : cut(known.brand());
        String signature = String.join("\n", String.valueOf(brand), String.join(",", channels), String.join(",", mods));
        synchronized (state) {
            if (signature.equals(state.lastSent)) {
                return;
            }
        }
        try {
            this.backend.submitFingerprint(new ClientFingerprintCreateRequest().owner(owner).brand(brand).channels(channels).mods(mods));
            synchronized (state) {
                state.lastSent = signature;
            }
        } catch (ApiException e) {
            if (e.getCode() == 503) {
                this.pausedUntil = this.clock.instant().plus(PAUSE_AFTER_UNAVAILABLE);
                LOGGER.info("The backend has client fingerprinting switched off, not submitting for {}", PAUSE_AFTER_UNAVAILABLE);
            } else {
                LOGGER.debug("Client traits not submitted: {}", e.getMessage());
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Client traits not submitted: {}", e.getMessage());
        }
    }

    /** Sorted, without the {@code minecraft:} namespace every client has, and at most {@value #MAX_ENTRIES}. */
    private static List<String> limited(java.util.Collection<String> values) {
        return values.stream().filter(value -> !value.toLowerCase(Locale.ROOT).startsWith("minecraft:")).sorted().limit(MAX_ENTRIES).toList();
    }

    private static String cut(String brand) {
        return brand.length() <= MAX_BRAND ? brand : brand.substring(0, MAX_BRAND);
    }
}
