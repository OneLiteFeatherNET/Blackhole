package net.onelitefeather.blackhole.backend.analytics;

/**
 * One refresh run of the analytics views, holding the refresh lock. Every call runs on the same connection, each statement
 * on autocommit. {@link #close()} releases the lock.
 */
public interface RefreshSession extends AutoCloseable {

    /** Whether the view has been populated; an unpopulated view cannot be refreshed concurrently. */
    boolean isPopulated(String view);

    /** Refreshes the view, concurrently when {@code concurrently} is true. Throws when the refresh fails. */
    void refresh(String view, boolean concurrently);

    /** Records the time of a successful refresh of the view. */
    void recordRefreshed(String view, long refreshedAtMillis);

    @Override
    void close();
}
