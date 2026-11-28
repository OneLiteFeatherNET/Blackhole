package net.onelitefeather.blackhole.backend.intel;

/**
 * The derived health of one list source, shown in the panel. The thresholds are defined by
 * {@link IntelListStatusService#health}.
 */
public enum IntelSourceHealth {
    /** The last refresh succeeded no more than two refresh intervals ago. */
    FRESH,
    /** No refresh error, but the last success is older than two refresh intervals, or none was ever stored. */
    STALE,
    /** The last refresh returned an error; the previous generation is still in use. */
    FAILED
}
