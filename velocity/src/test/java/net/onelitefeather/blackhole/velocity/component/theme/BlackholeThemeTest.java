package net.onelitefeather.blackhole.velocity.component.theme;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackholeThemeTest {

    /** The characters outside ASCII the default Minecraft font renders and the style allows. */
    private static final String ALLOWED_GLYPHS = "✔✖⚠»·→▸−";

    private static Component render(String text) {
        return BlackholeTheme.miniMessage().deserialize(text);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** The text component with exactly this content, searched depth first, with the style it inherits. */
    private static TextComponent find(Component component, String content) {
        return find(component, content, Style.empty());
    }

    private static TextComponent find(Component component, String content, Style inherited) {
        Style effective = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text && text.content().equals(content)) {
            return text.style(effective);
        }
        for (Component child : component.children()) {
            TextComponent found = find(child, content, effective);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void texts(Component component, List<String> out) {
        if (component instanceof TextComponent text) {
            out.add(text.content());
        }
        component.children().forEach(child -> texts(child, out));
    }

    private static void assertColour(String tag, NamedTextColor expected) {
        TextComponent value = find(render("<bh:" + tag + ">value</bh:" + tag + ">"), "value");

        assertNotNull(value, "<bh:" + tag + "> must keep its content");
        assertEquals(expected, value.color(), "<bh:" + tag + "> colour");
    }

    @Test
    void successIsGreen() {
        assertColour("success", NamedTextColor.GREEN);
    }

    @Test
    void errorIsRed() {
        assertColour("error", NamedTextColor.RED);
    }

    @Test
    void warningIsYellow() {
        assertColour("warning", NamedTextColor.YELLOW);
    }

    @Test
    void infoIsGrey() {
        assertColour("info", NamedTextColor.GRAY);
    }

    @Test
    void mutedIsDarkGrey() {
        assertColour("muted", NamedTextColor.DARK_GRAY);
    }

    @Test
    void sectionIsAqua() {
        assertColour("section", NamedTextColor.AQUA);
    }

    @Test
    void durationIsYellow() {
        assertColour("duration", NamedTextColor.YELLOW);
    }

    @Test
    void idIsGold() {
        assertColour("id", NamedTextColor.GOLD);
    }

    @Test
    void keyIsLightPurple() {
        assertColour("key", NamedTextColor.LIGHT_PURPLE);
    }

    @Test
    void upIsGreen() {
        assertColour("up", NamedTextColor.GREEN);
    }

    @Test
    void downIsRed() {
        assertColour("down", NamedTextColor.RED);
    }

    @Test
    void playerIsWhiteAndBold() {
        TextComponent value = find(render("<bh:player>value</bh:player>"), "value");

        assertNotNull(value);
        assertEquals(NamedTextColor.WHITE, value.color(), "player colour");
        assertEquals(TextDecoration.State.TRUE, value.decoration(TextDecoration.BOLD), "player names are bold");
    }

    @Test
    void otherValuesAreNotBold() {
        for (String tag : new String[]{"section", "duration", "id", "key", "success", "error"}) {
            TextComponent value = find(render("<bh:" + tag + ">value</bh:" + tag + ">"), "value");

            assertNotNull(value);
            assertTrue(value.decoration(TextDecoration.BOLD) != TextDecoration.State.TRUE, "<bh:" + tag + "> must not be bold");
        }
    }

    @Test
    void prefixIsDarkPurpleNameAndDarkGreySeparator() {
        Component prefix = render("<bh:prefix>");

        assertEquals("Blackhole » ", plain(prefix));
        assertEquals(NamedTextColor.DARK_PURPLE, find(prefix, "Blackhole").color(), "name colour");
        assertEquals(NamedTextColor.DARK_GRAY, find(prefix, " » ").color(), "separator colour");
    }

    @Test
    void okIsAGreenCheckMark() {
        Component ok = render("<bh:ok>");

        assertEquals("✔ ", plain(ok));
        assertEquals(NamedTextColor.GREEN, find(ok, "✔ ").color());
    }

    @Test
    void errIsARedCross() {
        Component err = render("<bh:err>");

        assertEquals("✖ ", plain(err));
        assertEquals(NamedTextColor.RED, find(err, "✖ ").color());
    }

    @Test
    void warnIsAYellowWarningSign() {
        Component warn = render("<bh:warn>");

        assertEquals("⚠ ", plain(warn));
        assertEquals(NamedTextColor.YELLOW, find(warn, "⚠ ").color());
    }

    @Test
    void sepIsADarkGreyMiddleDot() {
        Component sep = render("<bh:sep>");

        assertEquals(" · ", plain(sep));
        assertEquals(NamedTextColor.DARK_GRAY, find(sep, " · ").color());
    }

    @Test
    void insertingTagsDoNotColourTheTextAfterThem() {
        TextComponent after = find(render("<bh:ok>done"), "done");

        assertNotNull(after);
        assertEquals(null, after.color(), "text after an inserting tag keeps the default colour");
    }

    @Test
    void tagsNestLikeMiniMessageTags() {
        Component nested = render("<bh:info>Lifted <bh:player>Alex</bh:player> now</bh:info>");

        assertEquals("Lifted Alex now", plain(nested));
        assertEquals(NamedTextColor.GRAY, find(nested, "Lifted ").color(), "outer tag colours its own text");
        assertEquals(NamedTextColor.WHITE, find(nested, "Alex").color(), "inner tag wins for its content");
        assertEquals(NamedTextColor.GRAY, find(nested, " now").color(), "outer tag resumes after the inner one");
    }

    @Test
    void theStandardTagsStillWork() {
        assertEquals(NamedTextColor.RED, find(render("<red>x</red>"), "x").color());
    }

    @Test
    void anUnknownSubTagStaysPlainText() {
        assertEquals("<bh:bogus>x", plain(render("<bh:bogus>x")), "an unknown bh tag must not be swallowed");
    }

    @Test
    void theThemeDefinesExactlyTheDocumentedTags() {
        assertEquals(new TreeSet<>(Set.of("prefix", "ok", "err", "warn", "success", "error", "warning", "info", "player",
                "section", "duration", "id", "key", "up", "down", "muted", "sep")), new TreeSet<>(BlackholeTheme.tagNames()));
    }

    @Test
    void onlyAllowedGlyphsAppearOutsideAscii() {
        for (String name : BlackholeTheme.tagNames()) {
            List<String> texts = new ArrayList<>();
            texts(render("<bh:" + name + ">x</bh:" + name + ">"), texts);
            for (String text : texts) {
                text.chars().filter(c -> c > 127).forEach(c -> assertTrue(ALLOWED_GLYPHS.indexOf(c) >= 0,
                        "<bh:" + name + "> uses the glyph '" + (char) c + "' which the style does not allow"));
            }
        }
    }
}
