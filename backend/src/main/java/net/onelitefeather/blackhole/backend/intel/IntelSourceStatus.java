package net.onelitefeather.blackhole.backend.intel;

/**
 * The refresh status of one list source, as the last refresh left it. Times are epoch milliseconds; null when never.
 */
public record IntelSourceStatus(String sourceKey, Long lastAttemptAt, Long lastSuccessAt, String lastError, long entryCount,
                                long invalidCount) {
}
