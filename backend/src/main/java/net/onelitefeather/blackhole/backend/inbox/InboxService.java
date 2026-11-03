package net.onelitefeather.blackhole.backend.inbox;

import java.util.UUID;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import java.util.Objects;
import java.time.Duration;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import io.micronaut.context.annotation.Value;
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

    private static final String APPEAL_FILED = CaseEventPayload.APPEAL_FILED;
    private static final String APPEAL_DECIDED = CaseEventPayload.APPEAL_DECIDED;

    private final InboxSource source;
    private final Clock clock;
    private final Duration changedWindow;

    public InboxService(InboxSource source, Clock clock, @Value("${blackhole.inbox.changed-window:PT24H}") Duration changedWindow) {
        this.source = source;
        this.clock = clock;
        this.changedWindow = changedWindow;
    }

    /** The inbox as the caller reads it; {@code caller} is left out of the changed section. */
    public InboxDTO summary(UUID caller) {
        try {
            long now = this.clock.millis();
            return new InboxDTO.Response(
                    now,
                    section(InboxSection.NEEDS_ANSWER),
                    section(InboxSection.OPEN_APPEALS),
                    section(InboxSection.NEW_REPORTS),
                    section(InboxSection.UNASSIGNED_CASES),
                    changedByOthers(caller, now));
        } catch (InboxUnavailableException e) {
            return new InboxDTO.Error(ErrorKind.UNAVAILABLE, "the inbox could not be read, try again shortly");
        }
    }

    /**
     * The cases and appeals whose newest event falls in the window and was made by a verified staff member other than the caller.
     * Newest first; the count is the full number, the items are at most {@link #ITEMS_PER_SECTION}.
     */
    private InboxDTO.ChangedSection changedByOthers(UUID caller, long now) {
        long since = now - this.changedWindow.toMillis();
        List<InboxDTO.ChangedItem> rows = this.source.latestChangesSince(since).stream()
                .filter(change -> change.occurredAt() >= since)
                .filter(change -> change.actorTrust() == ActorTrust.VERIFIED && change.actorOid() != null)
                .filter(change -> !change.actorOid().equals(caller))
                .sorted(Comparator.comparingLong(LatestChange::occurredAt).reversed()
                        .thenComparing(LatestChange::punishmentIdentifier))
                .map(InboxService::changed)
                .toList();
        return new InboxDTO.ChangedSection(rows.size(), rows.stream().limit(ITEMS_PER_SECTION).toList());
    }

    private static InboxDTO.ChangedItem changed(LatestChange change) {
        boolean appeal = change.appealId() != null
                && (APPEAL_FILED.equals(change.type()) || APPEAL_DECIDED.equals(change.type()));
        String shortId = Objects.requireNonNullElse(change.shortId(), change.punishmentIdentifier());
        return new InboxDTO.ChangedItem(
                appeal ? InboxChangeKind.APPEAL : InboxChangeKind.CASE,
                appeal ? change.appealId() : shortId,
                change.shortId(),
                InboxChange.of(change.type()),
                change.actorName(),
                change.occurredAt());
    }

    private InboxDTO.Section section(InboxSection section) {
        List<InboxDTO.Item> items = this.source.oldest(section, ITEMS_PER_SECTION).stream()
                .sorted(OLDEST_FIRST)
                .limit(ITEMS_PER_SECTION)
                .toList();
        return new InboxDTO.Section(this.source.count(section), items);
    }
}
