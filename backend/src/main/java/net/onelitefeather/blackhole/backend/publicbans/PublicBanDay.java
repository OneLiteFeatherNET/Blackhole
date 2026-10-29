package net.onelitefeather.blackhole.backend.publicbans;

import java.time.LocalDate;

/** The UTC calendar day of an instant, the only time granularity the public ban list publishes. */
public final class PublicBanDay {

    static final long MILLIS_PER_DAY = 86_400_000L;

    private PublicBanDay() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The epoch day (UTC) of an epoch-millisecond instant. Independent of the JVM's default zone. */
    public static long of(long epochMillis) {
        return Math.floorDiv(epochMillis, MILLIS_PER_DAY);
    }

    /** The UTC calendar date of an epoch day. */
    public static LocalDate toDate(long epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }
}
