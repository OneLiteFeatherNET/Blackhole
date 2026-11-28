package net.onelitefeather.blackhole.backend.inbox;

import java.util.List;

/**
 * Read-only queries behind the inbox. A port so {@link InboxService} can be tested without a database;
 * {@link InboxQueries} is the PostgreSQL implementation.
 */
public interface InboxSource {

    /** Up to {@code limit} items of the section, oldest first. */
    List<InboxDTO.Item> oldest(InboxSection section, int limit);

    /** How many items the section has, without a cap. */
    long count(InboxSection section);

    /**
     * The newest case event of every punishment whose newest event is at or after {@code since} (epoch millis). Whether the actor
     * is verified and is not the caller is decided by {@link InboxService}, not here.
     */
    List<LatestChange> latestChangesSince(long since);
}
