package net.onelitefeather.blackhole.discord.text;

import net.onelitefeather.blackhole.markdown.DiscordRenderer;
import net.onelitefeather.blackhole.markdown.MarkdownParser;
import net.onelitefeather.blackhole.markdown.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DiscordMarkdownTest {

    @Test
    void underline_markers_are_removed_and_the_text_kept() {
        assertEquals("word", DiscordMarkdown.toSource("__word__"), "underline");
        assertEquals("a word b", DiscordMarkdown.toSource("a __word__ b"), "inside a sentence");
    }

    @Test
    void spoiler_markers_are_removed_and_the_text_kept() {
        assertEquals("secret", DiscordMarkdown.toSource("||secret||"), "spoiler");
        assertEquals("a secret b", DiscordMarkdown.toSource("a ||secret|| b"), "inside a sentence");
    }

    @Test
    void heading_markers_at_the_line_start_are_removed() {
        assertEquals("Title", DiscordMarkdown.toSource("# Title"), "h1");
        assertEquals("Title", DiscordMarkdown.toSource("## Title"), "h2");
        assertEquals("Title", DiscordMarkdown.toSource("### Title"), "h3");
        assertEquals("small", DiscordMarkdown.toSource("-# small"), "subtext");
        assertEquals("one\ntwo\nthree", DiscordMarkdown.toSource("# one\n## two\nthree"), "per line");
    }

    @Test
    void a_hash_inside_a_line_is_not_a_heading() {
        assertEquals("issue # 4 and -# no", DiscordMarkdown.toSource("issue # 4 and -# no"), "only the line start counts");
    }

    @Test
    void a_list_item_is_not_taken_for_a_subtext_line() {
        assertEquals("- item\n- other", DiscordMarkdown.toSource("- item\n- other"), "list markers stay");
    }

    @Test
    void user_role_and_channel_mentions_become_names_without_ids() {
        String out = DiscordMarkdown.toSource("hi <@123456789012345678> and <@!42> in <@&987654321098765432> see <#555555555555555555>");

        assertEquals("hi @user and @user in @role see #channel", out, "mentions");
        assertFalse(out.matches(".*\\d{5,}.*"), "no Discord id survives: " + out);
    }

    @Test
    void custom_emoji_become_their_name() {
        assertEquals("nice :blobwave: and :party:", DiscordMarkdown.toSource("nice <:blobwave:123456789012345678> and <a:party:223456789012345678>"),
                "static and animated emoji");
    }

    @Test
    void ids_inside_code_are_replaced_too() {
        String out = DiscordMarkdown.toSource("`<@123456789012345678>` and\n```\n<#555555555555555555>\n```");

        assertEquals("`@user` and\n```\n#channel\n```", out, "code keeps its shape but no id");
    }

    @Test
    void underline_spoiler_and_heading_markers_inside_code_are_kept() {
        assertEquals("`__x__` `||y||`", DiscordMarkdown.toSource("`__x__` `||y||`"), "inline code");
        assertEquals("```\n# not a heading\n__x__\n```", DiscordMarkdown.toSource("```\n# not a heading\n__x__\n```"), "fence");
    }

    @Test
    void an_escaped_marker_is_not_removed() {
        assertEquals("\\_\\_x\\_\\_", DiscordMarkdown.toSource("\\_\\_x\\_\\_"), "escaped underscores are literal text");
    }

    @Test
    void an_angle_bracket_link_becomes_the_bare_url() {
        assertEquals("docs https://example.org/a", DiscordMarkdown.toSource("docs <https://example.org/a>"), "the brackets only stopped the embed");
    }

    @Test
    void the_marks_the_subset_shares_are_kept_as_written() {
        String text = "**bold** *em* ~~del~~ `code`\n> quote\n- one\n- two\n1. first";

        assertEquals(text, DiscordMarkdown.toSource(text), "the same marks");
    }

    @Test
    void null_and_empty_are_empty() {
        assertEquals("", DiscordMarkdown.toSource(null), "null");
        assertEquals("", DiscordMarkdown.toSource(""), "empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "plain text", "**bold**", "*em*", "~~del~~", "`code`", "**a *b* c**", "line one\nline two", "a\n\nb", "> quote",
            "- one\n- two", "1. one\n2. two", "```\ncode\n```", "https://example.org/path", "100% *sure* (really) [x]", "a_b_c", "1. not\\. a list"
    })
    void source_to_discord_to_source_gives_the_same_tree_for_the_subset(String source) {
        java.util.List<Node> tree = new MarkdownParser().parse(source);

        String discord = DiscordRenderer.render(tree);
        java.util.List<Node> back = new MarkdownParser().parse(DiscordMarkdown.toSource(discord));

        assertEquals(Node.toMaps(tree), Node.toMaps(back), "round trip of '" + source + "' through '" + discord + "'");
    }
}
