package net.onelitefeather.blackhole.backend.inbox;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles the inbox. Each section gets its full count and at most {@link #ITEMS_PER_SECTION} items, oldest
 * first; the source already bounds its query, the cap here keeps the contract when it does not.
 */
@Singleton
public class InboxService {

    static final int ITEMS_PER_SECTION = 10;

    private static final Comparator<InboxDTO.Item> OLDEST_FIRST =
            Comparator.comparingLong(InboxDTO.Item::since).thenComparing(InboxDTO.Item::id);

    private final InboxSource source;
    private final Clock clock;

    public InboxService(InboxSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public InboxDTO summary() {
        try {
            return new InboxDTO.Response(
                    this.clock.millis(),
                    section(InboxSection.NEEDS_ANSWER),
                    section(InboxSection.OPEN_APPEALS),
                    section(InboxSection.NEW_REPORTS),
                    section(InboxSection.UNASSIGNED_CASES));
        } catch (InboxUnavailableException e) {
            return new InboxDTO.Error(ErrorKind.UNAVAILABLE, "the inbox could not be read, try again shortly");
        }
    }

    private InboxDTO.Section section(InboxSection section) {
        List<InboxDTO.Item> items = this.source.oldest(section, ITEMS_PER_SECTION).stream()
                .sorted(OLDEST_FIRST)
                .limit(ITEMS_PER_SECTION)
                .toList();
        return new InboxDTO.Section(this.source.count(section), items);
    }
}
