package net.onelitefeather.blackhole.backend.areaban;

import java.util.Optional;
import java.util.regex.Pattern;

/** Turns what a staff member typed into the canonical value of an area. Pure. */
public final class AreaBanNormalizer {

    private static final Pattern ASN = Pattern.compile("^(?i:AS)?[0-9]{1,10}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Za-z]{2}$");
    private static final long MAX_ASN = 4_294_967_295L;

    private AreaBanNormalizer() {
    }

    /** {@code AS16276}, {@code as16276} and {@code 16276} all become {@code 16276}. */
    public static Optional<String> asn(String raw) {
        if (raw == null || !ASN.matcher(raw.trim()).matches()) {
            return Optional.empty();
        }
        String digits = raw.trim().replaceFirst("(?i)^AS", "");
        long number = Long.parseLong(digits);
        return number == 0 || number > MAX_ASN ? Optional.empty() : Optional.of(Long.toString(number));
    }

    /** {@code de} becomes {@code DE}. */
    public static Optional<String> country(String raw) {
        if (raw == null || !COUNTRY.matcher(raw.trim()).matches()) {
            return Optional.empty();
        }
        return Optional.of(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
