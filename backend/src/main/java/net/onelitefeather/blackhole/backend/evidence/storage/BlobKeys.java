package net.onelitefeather.blackhole.backend.evidence.storage;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/** Server-generated object keys: {@code yyyy/MM/<random uuid>}. Client file names never reach a key. */
public final class BlobKeys {

    private static final Pattern GENERATED = Pattern.compile("^\\d{4}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** A fresh key, prefixed with the year and month of the clock so listings stay browsable. */
    public static String generate(Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        return String.format("%04d/%02d/%s", now.getYear(), now.getMonthValue(), UUID.randomUUID());
    }

    /** Whether the key has the shape {@link #generate} produces. */
    public static boolean isGenerated(String key) {
        return key != null && GENERATED.matcher(key).matches();
    }

    private BlobKeys() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
