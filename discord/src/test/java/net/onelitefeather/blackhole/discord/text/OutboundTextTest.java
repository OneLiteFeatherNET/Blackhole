package net.onelitefeather.blackhole.discord.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundTextTest {

    private static final String ZWSP = "​";

    @Test
    void formatting_is_mapped_to_discord_marks() {
        assertEquals(List.of("**ok** and *em* and ~~del~~ and `code`"), OutboundText.chunks("**ok** and *em* and ~~del~~ and `code`"), "same marks");
    }

    @Test
    void a_link_is_written_with_angle_brackets_so_no_embed_appears() {
        assertEquals(List.of("**ok** see docs <https://example.org/>"), OutboundText.chunks("**ok** see [docs](https://example.org)"), "text then <url>");
    }

    @Test
    void a_bare_link_is_written_in_angle_brackets() {
        assertEquals(List.of("<https://example.org/>"), OutboundText.chunks("https://example.org"), "bare link");
    }

    @Test
    void everyone_and_here_are_broken_up() {
        String out = OutboundText.chunks("@everyone @here").getFirst();

        assertEquals("@" + ZWSP + "everyone @" + ZWSP + "here", out, "a zero-width space follows the at sign");
    }

    @Test
    void user_and_role_mentions_in_literal_text_cannot_resolve() {
        String out = OutboundText.chunks("<@123456789012345678> <@&987654321098765432>").getFirst();

        assertFalse(java.util.regex.Pattern.compile("(?<!\\\\)<@").matcher(out).find(), "no unescaped mention form: " + out);
        assertFalse(out.replace("@" + ZWSP, "").contains("@"), "every at sign is followed by a zero-width space: " + out);
    }

    @Test
    void an_empty_source_has_no_chunks() {
        assertEquals(List.of(), OutboundText.chunks(""), "nothing to show");
        assertEquals(List.of(), OutboundText.chunks(null), "null");
    }

    @Test
    void a_long_message_is_split_at_paragraph_boundaries_under_2000() {
        String source = ("word ".repeat(150).trim() + "\n\n").repeat(6);

        List<String> chunks = OutboundText.chunks(source);

        assertTrue(chunks.size() > 1, "split into several posts: " + chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 2000, "a chunk is " + chunk.length() + " characters");
            assertTrue(chunk.startsWith("word") && chunk.endsWith("word"), "a chunk holds whole paragraphs");
        }
    }

    @Test
    void one_huge_paragraph_is_split_at_line_breaks_and_never_over_2000() {
        String source = ("line of text " + "x".repeat(80) + "\n").repeat(60);

        List<String> chunks = OutboundText.chunks(source);

        assertTrue(chunks.size() > 1, "split: " + chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 2000, "a chunk is " + chunk.length() + " characters");
            assertFalse(chunk.endsWith("\n") || chunk.startsWith("\n"), "cut between lines, not inside one");
        }
    }

    @Test
    void literal_text_cannot_ping_or_format() {
        String out = OutboundText.literal("@everyone *x* [a](b)");

        assertFalse(out.contains("@everyone"), "no ping: " + out);
        assertTrue(out.contains("\\*x\\*"), "markup is escaped: " + out);
    }

    @Test
    void an_excerpt_is_one_code_block_with_a_fence_longer_than_any_backtick_run_inside() {
        List<String> blocks = OutboundText.codeBlocks("Player 2: ```hello```\nYou: ok");

        assertEquals(1, blocks.size(), "one block");
        assertTrue(blocks.getFirst().startsWith("````\n") && blocks.getFirst().endsWith("\n````"), "four backticks around three: " + blocks.getFirst());
    }

    @Test
    void a_plain_excerpt_gets_a_three_backtick_fence() {
        assertEquals(List.of("```\nPlayer 2: hi\nYou: ok\n```"), OutboundText.codeBlocks("Player 2: hi\nYou: ok"), "fenced lines");
    }

    @Test
    void mentions_in_an_excerpt_are_broken_up() {
        String block = OutboundText.codeBlocks("Player 2: @everyone").getFirst();

        assertFalse(block.contains("@everyone"), "no ping inside the block: " + block);
    }

    @Test
    void a_long_excerpt_is_split_between_lines_into_blocks_under_2000() {
        String lines = ("Player 2: " + "y".repeat(90) + "\n").repeat(40).trim();

        List<String> blocks = OutboundText.codeBlocks(lines);

        assertTrue(blocks.size() > 1, "several blocks");
        for (String block : blocks) {
            assertTrue(block.length() <= 2000, "a block is " + block.length() + " characters");
            assertTrue(block.startsWith("```") && block.endsWith("```"), "every block is fenced");
        }
    }
}
