package net.onelitefeather.blackhole.discord.leak;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The candidate format as a table: what is a candidate, what is not, and how it is normalised. */
class CandidateTableTest {

    @Test
    void prefixed_codes_in_every_accepted_spelling_become_one_normalised_candidate() {
        Map<String, String> table = Map.ofEntries(
                Map.entry("#K7M29QXA", "K7M29QXA"),
                Map.entry("#k7m29qxa", "K7M29QXA"),
                Map.entry("#k7m2-9qxa", "K7M29QXA"),
                Map.entry("#K7M2 9QXA", "K7M29QXA"),
                Map.entry("my code is #K7M2-9QXA, please look", "K7M29QXA"),
                Map.entry("(#k7m29qxa)", "K7M29QXA"),
                Map.entry("**#K7M29QXA**", "K7M29QXA"),
                Map.entry("||#K7M2-9QXA||", "K7M29QXA"),
                Map.entry("#K7M29QXA.", "K7M29QXA"),
                Map.entry("#ILO1-0000", "11010000"),
                Map.entry("#12345678", "12345678"),
                Map.entry("#deadbeef", "DEADBEEF"));

        table.forEach((input, expected) ->
                assertEquals(List.of(expected), LeakScanner.candidates(input), "input: " + input));
    }

    @Test
    void anything_that_is_not_the_exact_format_is_no_candidate() {
        List<String> rejected = List.of(
                "ABCDEFGH",
                "12345678",
                "K7M2-9QXA",
                "k7m29qxa",
                "#K7M2-9QX",
                "#K7M29QXAB",
                "#K7M2  9QXA",
                "#K7M2--9QXA",
                "#K7M2_9QXA",
                "# K7M29QXA",
                "word#K7M29QXA",
                "#K7M29QXA_",
                "#UUUUUUUU",
                "#HELLO",
                "#hashtag",
                "#K7M29QXÄ",
                "");

        for (String input : rejected) {
            assertEquals(List.of(), LeakScanner.candidates(input), "input: " + input);
        }
    }

    @Test
    void a_bare_word_or_a_hex_number_without_the_prefix_is_never_a_candidate() {
        assertEquals(List.of(), LeakScanner.candidates("The word ABCDEFGH and the number 12345678 and 0xDEADBEEF and cafebabe"), "bare tokens");
    }

    @Test
    void at_most_five_candidates_are_taken_from_one_message() {
        String seven = "#AAAAAAA1 #AAAAAAA2 #AAAAAAA3 #AAAAAAA4 #AAAAAAA5 #AAAAAAA6 #AAAAAAA7";

        List<String> candidates = LeakScanner.candidates(seven);

        assertEquals(List.of("AAAAAAA1", "AAAAAAA2", "AAAAAAA3", "AAAAAAA4", "AAAAAAA5"), candidates, "the first five, in order");
    }

    @Test
    void the_same_code_written_twice_is_sent_once() {
        assertEquals(List.of("K7M29QXA"), LeakScanner.candidates("#K7M2-9QXA and again #k7m29qxa"), "deduplicated");
    }

    @Test
    void two_different_codes_in_one_message_are_both_candidates_in_order() {
        assertEquals(List.of("K7M29QXA", "ABCDEFGH"), LeakScanner.candidates("#K7M29QXA / #ABCD-EFGH"), "both");
    }
}
