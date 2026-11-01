package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of the case API: the case row, a page of the timeline with the actors' names, related
 * punishments and the case list. Reads never write. ({@link CaseEventReader} stays the plain read of
 * the whole log.)
 */
public interface CaseReader {

    /** The stored case row; empty when the punishment has no case yet. */
    record CaseRow(CaseMeta meta, @Nullable String assigneeName, long version, long createdAt, long updatedAt,
                   @Nullable Long banIdLeakedAt, @Nullable String banIdLeakReason, long playerSeenSeq) {

        /** Whether the Ban ID of the current generation is marked leaked. */
        public boolean leaked() {
            return this.banIdLeakedAt != null;
        }
    }

    /** Another punishment of the same owner. {@code state} is {@code ACTIVE}, {@code EXPIRED} or {@code LIFTED}. */
    record Related(String identifier, String shortId, @Nullable String section, String state, String caseStatus, long createdAt) {
    }

    /** One event with the display name of its actor (null when there is none or it was anonymized). */
    record Entry(CaseEventRecord event, @Nullable String actorName) {
    }

    /**
     * A page of the timeline.
     *
     * @param afterOccurredAt with {@code afterSeq}, the keyset cursor: the page starts after this event in the chosen order
     * @param types           exact types or group prefixes ({@code note}, {@code appeal}, ...); empty means all
     * @param limit           how many events to read (the caller asks for one more than it shows to learn whether there is a next page)
     */
    record TimelineQuery(
            String punishmentIdentifier, @Nullable Long afterOccurredAt, @Nullable Long afterSeq, List<String> types,
            @Nullable ActorTrust trust, @Nullable Long from, @Nullable Long to, boolean ascending, int limit
    ) {
    }

    /**
     * One row of the case list. {@code playerName} is the newest name of the owner in the name history (null when none);
     * {@code owner} is the full hash, which the service never returns to the caller in a list item.
     */
    record Listed(
            String identifier, String shortId, @Nullable String section, String state, CaseMeta meta, @Nullable String assigneeName,
            long createdAt, long lastActivityAt, @Nullable String playerName, String owner, long version
    ) {
    }

    /**
     * The filters of the case list; a {@code null} filter does not filter. {@code owner} narrows the list to one player
     * (the full owner hash, validated by the caller).
     */
    record ListQuery(
            @Nullable String status, @Nullable UUID assignee, @Nullable String tag, @Nullable String priority,
            @Nullable String section, @Nullable String state, int page, int size, @Nullable String owner
    ) {

        public ListQuery(@Nullable String status, @Nullable UUID assignee, @Nullable String tag, @Nullable String priority,
                         @Nullable String section, @Nullable String state, int page, int size) {
            this(status, assignee, tag, priority, section, state, page, size, null);
        }
    }

    /** One page of the case list. */
    record ListPage(List<Listed> items, long total) {
    }

    /**
     * One player in the groups: the cases of {@code owner} that match the filters. {@code openCount} counts the cases whose
     * status is not {@code CLOSED}; {@code lastActivityAt} is the newest event (or the creation) of any of them.
     */
    record Group(String owner, @Nullable String playerName, long openCount, long totalCount, long lastActivityAt) {
    }

    /** One page of groups; the total counts groups, not cases. */
    record GroupPage(List<Group> items, long total) {
    }

    Optional<CaseRow> find(String punishmentIdentifier);

    /** Up to {@code limit} other punishments of {@code owner}, newest first. */
    List<Related> related(String owner, String excludedIdentifier, long now, int limit);

    /** The events of the query, in the order it asks for, at most {@code limit}. */
    List<Entry> timeline(TimelineQuery query);

    /** Every event about one subject (a note: its versions and its redaction), in timeline order. */
    List<CaseEventRecord> subject(String punishmentIdentifier, String subjectId);

    ListPage list(ListQuery query, long now);

    /** One page of players with a matching case, ordered by last activity, newest first. {@code query.owner} is ignored. */
    GroupPage groups(ListQuery query, long now);
}
