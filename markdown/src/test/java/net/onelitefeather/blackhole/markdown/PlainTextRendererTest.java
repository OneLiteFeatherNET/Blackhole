package net.onelitefeather.blackhole.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlainTextRendererTest {

    private static String render(String source) {
        return PlainTextRenderer.render(new MarkdownParser().parse(source));
    }

    @Test
    void marksAreDroppedAndTextIsKept() {
        assertEquals("bold italic gone code", render("**bold** *italic* ~~gone~~ `code`"), "inline marks vanish");
    }

    @Test
    void aLinkShowsItsTargetAfterTheText() {
        assertEquals("docs (https://example.org/x)", render("[docs](https://example.org/x)"), "text, then the target");
    }

    @Test
    void aLinkWhoseTextIsTheTargetIsShownOnce() {
        assertEquals("https://example.org/", render("<https://example.org/>"), "no duplicate");
    }

    @Test
    void blocksAreSeparatedByABlankLine() {
        assertEquals("a\n\nb\n\n- c\n- d", render("a\n\nb\n\n- c\n- d"), "paragraphs and a list");
    }

    @Test
    void anEmptyTreeIsEmpty() {
        assertEquals("", PlainTextRenderer.render(List.of()), "nothing in, nothing out");
    }

    @Test
    void textIsNeverEscaped() {
        assertEquals("<b>x</b> @everyone", render("<b>x</b> @everyone"), "plain text is for people, not for a markup target");
    }
}
