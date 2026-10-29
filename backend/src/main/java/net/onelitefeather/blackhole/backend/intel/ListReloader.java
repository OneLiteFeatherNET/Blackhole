package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loads the active list generations into this replica's in-memory classifier, at start-up and then on a poll. A poll
 * reads only the generation pointers and loads the entries when one of them, or the set of enabled sources, changed.
 * Replicas never write the lists; only the refresh job does.
 */
@Singleton
public class ListReloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListReloader.class);

    private final ListGenerationStore store;
    private final IntelClassifierHolder holder;
    private final List<IntelListSourceConfig> sources;
    private final Set<Long> hostingAsns;
    /** The generations the current classifier was built from; null until the first load. */
    private Map<String, Long> loaded;

    public ListReloader(
            ListGenerationStore store,
            IntelClassifierHolder holder,
            List<IntelListSourceConfig> sources,
            @Value("${blackhole.intel.hosting-asns:}") String hostingAsns
    ) {
        this.store = store;
        this.holder = holder;
        this.sources = sources;
        this.hostingAsns = parseAsns(hostingAsns);
    }

    /** @return the comma-separated AS numbers; an entry that is not a number fails the start-up, on purpose */
    static Set<Long> parseAsns(String csv) {
        Set<Long> asns = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return asns;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                asns.add(Long.parseLong(trimmed));
            }
        }
        return asns;
    }

    @EventListener
    void onStartup(StartupEvent event) {
        reloadQuietly();
    }

    @Scheduled(
            fixedDelay = "${blackhole.intel.lists.reload-interval:PT5M}",
            initialDelay = "${blackhole.intel.lists.reload-interval:PT5M}"
    )
    void poll() {
        reloadQuietly();
    }

    /**
     * @return true when the classifier was rebuilt, false when the active generations and the enabled sources are the
     * same as at the last build
     */
    public synchronized boolean reloadIfChanged() {
        Map<String, IntelListSourceConfig> enabled = new HashMap<>();
        for (IntelListSourceConfig source : this.sources) {
            if (source.isEnabled()) {
                enabled.put(source.getKey(), source);
            }
        }
        Map<String, Long> fingerprint = new TreeMap<>();
        this.store.activeGenerations().forEach((key, generation) -> {
            if (enabled.containsKey(key)) {
                fingerprint.put(key, generation);
            }
        });
        if (fingerprint.equals(this.loaded)) {
            return false;
        }
        List<IntelClassifier.ListedPrefix> prefixes = new ArrayList<>();
        for (ListGenerationStore.ListedCidr row : this.store.loadActiveEntries()) {
            IntelListSourceConfig source = enabled.get(row.sourceKey());
            if (source == null) {
                continue;
            }
            IntelPrefix.parseCidr(row.cidr())
                    .ifPresent(prefix -> prefixes.add(new IntelClassifier.ListedPrefix(source.getKey(), source.getKind(), prefix)));
        }
        this.holder.replace(IntelClassifier.of(this.hostingAsns, prefixes));
        this.loaded = fingerprint;
        return true;
    }

    private void reloadQuietly() {
        try {
            if (reloadIfChanged()) {
                LOGGER.info("VPN and hosting classifier reloaded");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not reload the VPN and hosting classifier; keeping the last one");
        }
    }
}
