package net.onelitefeather.blackhole.backend.utils;

import java.util.regex.Pattern;

/**
 * Validity of the hashed owner every punishment, score and profile is keyed by: a SHA-512 hash as
 * 128 hex characters. Raw player UUIDs never reach the backend.
 */
public final class OwnerHash {

    private static final Pattern SHA_512_HEX = Pattern.compile("^[a-fA-F0-9]{128}$");

    public static boolean isValid(String owner) {
        return owner != null && SHA_512_HEX.matcher(owner).matches();
    }

    private OwnerHash() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
