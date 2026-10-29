package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Refreshes one list source as a new generation. The active generation stays in place until the new one is complete,
 * so a failed download, a malformed list or a failed write leaves readers with the last good list.
 */
@Singleton
public class ListRefreshService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListRefreshService.class);

    /** A source is refreshed at most once per this window, however often it is asked. */
    static final long DEBOUNCE_MILLIS = Duration.ofMinutes(10).toMillis();
    static final int BATCH_SIZE = 5000;

    public enum Outcome {
        /** The new generation is active. */
        REFRESHED,
        /** Another refresh of this source ran inside the debounce window. */
        SKIPPED,
        /** The previous generation is still active; the failure is recorded on the source. */
        FAILED
    }

    private final ListGenerationStore store;
    private final ListFetcher fetcher;
    private final Clock clock;

    public ListRefreshService(ListGenerationStore store, ListFetcher fetcher, Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public Outcome refresh(IntelListSourceConfig source) {
        String key = source.getKey();
        long now = this.clock.millis();
        if (!this.store.claimRefresh(key, now, DEBOUNCE_MILLIS)) {
            return Outcome.SKIPPED;
        }
        long generation = 0;
        boolean opened = false;
        try {
            ListParser.Parsed parsed = ListParser.parse(this.fetcher.fetch(source.getUrl()));
            List<String> cidrs = parsed.cidrs();
            long previous = this.store.activeEntryCount(key);
            if (previous > 0 && cidrs.size() * 2L < previous) {
                throw new ListRefreshException("the list shrank from " + previous + " to " + cidrs.size() + " entries");
            }
            generation = this.store.openGeneration(key, now);
            opened = true;
            for (int from = 0; from < cidrs.size(); from += BATCH_SIZE) {
                this.store.writeEntries(key, generation, cidrs.subList(from, Math.min(cidrs.size(), from + BATCH_SIZE)));
            }
            this.store.activate(key, generation, now);
            this.store.deleteOtherGenerations(key, generation);
            this.store.recordSuccess(key, now, cidrs.size(), parsed.invalid());
            LOGGER.info("List source {} refreshed: {} entries, {} invalid lines", key, cidrs.size(), parsed.invalid());
            return Outcome.REFRESHED;
        } catch (RuntimeException e) {
            if (opened) {
                discardQuietly(key, generation);
            }
            String message = e instanceof ListRefreshException ? e.getMessage() : "the refresh could not be stored";
            this.store.recordFailure(key, now, message);
            LOGGER.warn("List source {} refresh failed: {}", key, message);
            return Outcome.FAILED;
        }
    }

    private void discardQuietly(String key, long generation) {
        try {
            this.store.discardGeneration(key, generation);
        } catch (RuntimeException e) {
            // the sweeper removes a generation that is left behind
            LOGGER.warn("List source {} could not discard generation {}; the sweeper will remove it", key, generation);
        }
    }
}
