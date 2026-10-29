package net.onelitefeather.blackhole.velocity.guard;

import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AreaBanArgumentsTest {

    private static final Predicate<String> NOTHING = node -> false;
    private static final Predicate<String> COUNTRY_PERMISSION = node -> node.equals("blackhole.areaban.country");

    private static AreaBanArguments.Result parse(String input) {
        return AreaBanArguments.parse(input, COUNTRY_PERMISSION);
    }

    @Test
    void anAsnBanTakesItsDurationAndReason() {
        AreaBanArguments.Result result = parse("asn 16276 2h reason text");

        assertEquals(new AreaBanArguments.Ban(AreaBanArguments.Kind.ASN, "16276", 7200L, "reason text"), result,
                "the duration is read in seconds and the rest is the reason");
    }

    @Test
    void anAsnWithTheAsPrefixIsStoredAsDigits() {
        AreaBanArguments.Result result = parse("asn AS16276 7d botnet");

        assertEquals(new AreaBanArguments.Ban(AreaBanArguments.Kind.ASN, "16276", 604800L, "botnet"), result,
                "AS16276 is stored as 16276 and 7d is 604800 seconds");
    }

    @Test
    void aReasonWithoutDurationMeansAPermanentBan() {
        AreaBanArguments.Result result = parse("asn 16276 known botnet");

        assertEquals(new AreaBanArguments.Ban(AreaBanArguments.Kind.ASN, "16276", null, "known botnet"), result,
                "without a duration the ban has no expiry; the backend decides whether the actor may ban that way");
    }

    @Test
    void anAsnBanWithoutAReasonIsRefusedWithUsage() {
        AreaBanArguments.Result result = parse("asn 16276 2h");

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "a ban without a reason is refused");
    }

    @Test
    void anAsnBanWithOnlyTheNumberIsRefused() {
        AreaBanArguments.Result result = parse("asn 16276");

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "a ban with no reason at all is refused");
    }

    @Test
    void aNonNumericAsnIsRefused() {
        AreaBanArguments.Result result = parse("asn abc 1h reason");

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "an ASN must be digits, with or without AS");
    }

    @Test
    void aCountryBanWithoutThePermissionIsRefusedBeforeAnyCall() {
        AreaBanArguments.Result result = AreaBanArguments.parse("country DE 1h test", NOTHING);

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "a country ban needs blackhole.areaban.country");
    }

    @Test
    void aCountryBanWithThePermissionIsStoredInUppercase() {
        AreaBanArguments.Result result = parse("country de 1h test");

        assertEquals(new AreaBanArguments.Ban(AreaBanArguments.Kind.COUNTRY, "DE", 3600L, "test"), result,
                "the country code is stored in uppercase");
    }

    @Test
    void aThreeLetterCountryCodeIsRefused() {
        AreaBanArguments.Result result = parse("country DEU 1h test");

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "a country code is two letters");
    }

    @Test
    void aReasonLongerThanTheBackendAcceptsIsRefused() {
        AreaBanArguments.Result result = parse("asn 16276 " + "x".repeat(201));

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "a reason over 200 characters is refused");
    }

    @Test
    void aZeroDurationIsRefused() {
        AreaBanArguments.Result result = parse("asn 16276 0h reason");

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "a zero duration is refused, not read as a permanent ban");
    }

    @Test
    void liftNeedsAKindValueAndReason() {
        AreaBanArguments.Result result = parse("lift asn 16276 false positive");

        assertEquals(new AreaBanArguments.Lift(AreaBanArguments.Kind.ASN, "16276", "false positive"), result,
                "a lift names the area and gives the reason");
    }

    @Test
    void liftWithoutAReasonIsRefused() {
        AreaBanArguments.Result result = parse("lift asn 16276");

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "a lift without a reason is refused");
    }

    @Test
    void anUnknownKindIsRefusedWithUsage() {
        AreaBanArguments.Result result = parse("region 16276 1h reason");

        assertInstanceOf(AreaBanArguments.Invalid.class, result, "only asn and country are areas");
    }
}
