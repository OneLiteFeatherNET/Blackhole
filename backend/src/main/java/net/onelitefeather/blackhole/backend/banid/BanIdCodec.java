package net.onelitefeather.blackhole.backend.banid;

import java.util.Optional;

/**
 * The Ban ID text format: 8 characters of Crockford base32 (the digits and the letters without
 * {@code I L O U}, 40 bits). Typed input is normalised before any use.
 */
public final class BanIdCodec {

    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int LENGTH = 8;

    private BanIdCodec() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Removes whitespace and one leading {@code #}, upper-cases, and reads {@code O} as {@code 0} and
     * {@code I}/{@code L} as {@code 1}.
     *
     * @return the eight alphabet characters, empty if the input is anything else
     */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        StringBuilder stripped = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                stripped.append(c);
            }
        }
        if (!stripped.isEmpty() && stripped.charAt(0) == '#') {
            stripped.deleteCharAt(0);
        }
        if (stripped.length() != LENGTH) {
            return Optional.empty();
        }
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < stripped.length(); i++) {
            char c = Character.toUpperCase(stripped.charAt(i));
            c = switch (c) {
                case 'I', 'L' -> '1';
                case 'O' -> '0';
                default -> c;
            };
            if (ALPHABET.indexOf(c) < 0) {
                return Optional.empty();
            }
            code.append(c);
        }
        return Optional.of(code.toString());
    }
}
