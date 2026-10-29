package net.onelitefeather.blackhole.backend.intel;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListParserTest {

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validCidrLinesAreKept() {
        ListParser.Parsed parsed = ListParser.parse(body("198.51.100.0/24\n203.0.113.0/25\n"));

        assertEquals(List.of("198.51.100.0/24", "203.0.113.0/25"), parsed.cidrs(), "each valid CIDR line is one entry");
        assertEquals(0, parsed.invalid(), "no line was invalid");
    }

    @Test
    void commentAndBlankLinesAreIgnoredAndNotCounted() {
        ListParser.Parsed parsed = ListParser.parse(body("# generated\n\n198.51.100.0/24\r\n   \n"));

        assertEquals(List.of("198.51.100.0/24"), parsed.cidrs(), "comments, blank lines and CRLF endings are not entries");
        assertEquals(0, parsed.invalid(), "comment and blank lines are not invalid lines");
    }

    /** Enough valid lines that a few invalid ones stay far below the 5 percent limit. */
    private static String validLines(int count) {
        StringBuilder text = new StringBuilder();
        IntStream.range(0, count).forEach(i -> text.append("10.").append(i % 250).append(".0.0/16\n"));
        return text.toString();
    }

    @Test
    void anInvalidLineIsCountedAndSkipped() {
        ListParser.Parsed parsed = ListParser.parse(body(validLines(100) + "not-an-address/24\n"));

        assertEquals(100, parsed.cidrs().size(), "the valid lines are kept");
        assertEquals(1, parsed.invalid(), "the invalid line is counted");
    }

    @Test
    void aLineWithoutAPrefixLengthIsInvalid() {
        ListParser.Parsed parsed = ListParser.parse(body(validLines(100) + "198.51.100.1\n198.51.100.0/33\n"));

        assertEquals(100, parsed.cidrs().size(), "a bare address and an out-of-range prefix are not CIDR");
        assertEquals(2, parsed.invalid(), "both are counted as invalid");
    }

    @Test
    void ipv6CidrLinesAreAccepted() {
        ListParser.Parsed parsed = ListParser.parse(body("2001:550:1d05::/48\n"));

        assertEquals(List.of("2001:0550:1d05:0000:0000:0000:0000:0000/48"), parsed.cidrs(),
                "an IPv6 CIDR is kept in its normalised form");
    }

    @Test
    void aRepeatedLineIsKeptOnce() {
        ListParser.Parsed parsed = ListParser.parse(body("198.51.100.0/24\n198.51.100.0/24\n"));

        assertEquals(1, parsed.cidrs().size(), "a duplicate line is one entry");
    }

    @Test
    void exactlyFivePercentInvalidIsStillAccepted() {
        StringBuilder text = new StringBuilder();
        IntStream.range(0, 95).forEach(i -> text.append("10.").append(i % 250).append(".0.0/16\n"));
        IntStream.range(0, 5).forEach(i -> text.append("broken line ").append(i).append('\n'));

        ListParser.Parsed parsed = ListParser.parse(body(text.toString()));

        assertEquals(5, parsed.invalid(), "five of one hundred lines are invalid");
    }

    @Test
    void moreThanFivePercentInvalidFailsTheSource() {
        StringBuilder text = new StringBuilder();
        IntStream.range(0, 94).forEach(i -> text.append("10.").append(i % 250).append(".0.0/16\n"));
        IntStream.range(0, 6).forEach(i -> text.append("broken line ").append(i).append('\n'));

        ListRefreshException failed = assertThrows(ListRefreshException.class, () -> ListParser.parse(body(text.toString())),
                "six of one hundred invalid lines is above 5 percent");
        assertEquals(true, failed.getMessage().contains("invalid"), "the failure names the invalid lines: " + failed.getMessage());
    }

    @Test
    void anEmptyBodyFailsTheSource() {
        assertThrows(ListRefreshException.class, () -> ListParser.parse(body("# only a comment\n\n")),
                "a body with no entries fails the refresh");
    }
}
