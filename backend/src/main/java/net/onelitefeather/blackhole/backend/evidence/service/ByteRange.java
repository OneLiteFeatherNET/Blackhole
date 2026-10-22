package net.onelitefeather.blackhole.backend.evidence.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an HTTP {@code Range} header asks of a file of a known size. Only a single {@code bytes}
 * range is honoured; anything else that is not a valid range (another unit, several ranges, a
 * malformed spec, a reversed range) is ignored and the whole file is served, as RFC 9110 allows.
 */
public sealed interface ByteRange {

    Pattern SINGLE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    /** Serve the whole file with status 200. */
    record Full() implements ByteRange {
    }

    /** Serve {@code start..end} (inclusive) with status 206. */
    record Partial(long start, long end) implements ByteRange {
    }

    /** The range starts behind the end of the file: answer 416. */
    record Unsatisfiable() implements ByteRange {
    }

    /**
     * @param header the raw {@code Range} header, may be {@code null}
     * @param size   the file's size in bytes
     */
    static ByteRange parse(String header, long size) {
        if (header == null) {
            return new Full();
        }
        Matcher matcher = SINGLE.matcher(header.strip());
        if (!matcher.matches() || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
            return new Full();
        }
        try {
            if (matcher.group(1).isEmpty()) {
                long suffix = Long.parseLong(matcher.group(2));
                if (suffix == 0 || size == 0) {
                    return new Unsatisfiable();
                }
                return new Partial(Math.max(0, size - suffix), size - 1);
            }
            long start = Long.parseLong(matcher.group(1));
            long end = matcher.group(2).isEmpty() ? Long.MAX_VALUE : Long.parseLong(matcher.group(2));
            if (end < start) {
                return new Full();
            }
            if (start >= size) {
                return new Unsatisfiable();
            }
            return new Partial(start, Math.min(end, size - 1));
        } catch (NumberFormatException tooBig) {
            return new Full();
        }
    }
}
