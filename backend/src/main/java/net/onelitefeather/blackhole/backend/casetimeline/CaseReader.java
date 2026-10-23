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
    record CaseRow(CaseMeta meta, @Nullable String assigneeName, long version, long createdAt, long updatedAt) {
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

    /** One row of the case list. */
    record Listed(
            String identifier, String shortId, @Nullable String section, String state, CaseMeta meta, @Nullable String assigneeName,
            long createdAt, long lastActivityAt
    ) {
    }

    /** The filters of the case list; a {@code null} filter does not filter. */
    record ListQuery(
            @Nullable String status, @Nullable UUID assignee, @Nullable String tag, @Nullable String priority,
            @Nullable String section, @Nullable String state, int page, int size
    ) {
    }

    /** One page of the case list. */
    record ListPage(List<Listed> items, long total) {
    }

    Optional<CaseRow> find(String punishmentIdentifier);

    /** Up to {@code limit} other punishments of {@code owner}, newest first. */
    List<Related> related(String owner, String excludedIdentifier, long now, int limit);

    /** The events of the query, in the order it asks for, at most {@code limit}. */
    List<Entry> timeline(TimelineQuery query);

    /** Every event about one subject (a note: its versions and its redaction), in timeline order. */
    List<CaseEventRecord> subject(String punishmentIdentifier, String subjectId);

    ListPage list(ListQuery query, long now);
}
