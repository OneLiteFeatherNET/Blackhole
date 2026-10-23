package net.onelitefeather.blackhole.backend.casetimeline;

import java.util.regex.Pattern;

/** The shape of a case tag: a free-form lower case slug. */
public final class CaseTags {

    /** The most tags a case may carry. */
    public static final int MAX = 10;

    /** {@code [a-z0-9-]}, 1 to 32 characters. */
    public static final Pattern VALID = Pattern.compile("[a-z0-9-]{1,32}");

    private CaseTags() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
