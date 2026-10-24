package net.onelitefeather.blackhole.backend.evidence.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextGuardTest {

    @Test
    void ordinaryUtf8TextPasses() {
        assertTrue(TextGuard.isPlainText(ChatFixtures.utf8("Hello, Zoë!\r\nSecond line\twith a tab\n")));
    }

    @Test
    void aByteOrderMarkIsTolerated() {
        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};

        assertTrue(TextGuard.isPlainText(withBom));
    }

    @Test
    void malformedUtf8IsRefused() {
        assertFalse(TextGuard.isPlainText(new byte[]{'a', (byte) 0xC3, (byte) 0x28}), "an invalid continuation byte");
        assertFalse(TextGuard.isPlainText(new byte[]{(byte) 0xFF, (byte) 0xFE, 'a'}), "a byte that is never UTF-8");
    }

    @Test
    void aNulByteIsRefused() {
        assertFalse(TextGuard.isPlainText(new byte[]{'a', 0, 'b'}));
    }

    @Test
    void controlCharactersOtherThanWhitespaceAreRefused() {
        assertFalse(TextGuard.isPlainText(ChatFixtures.utf8("bell\u0007")));
        assertFalse(TextGuard.isPlainText(ChatFixtures.utf8("escape\u001b[31m")));
        assertFalse(TextGuard.isPlainText(ChatFixtures.utf8("c1\u0085control")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<svg xmlns='x'>", "<?xml version='1.0'?>", "<html>", "<!DOCTYPE html>", "<script>alert(1)</script>", "  \n\t<SVG>",
            "<Script src=x>"})
    void aTextThatStartsLikeADocumentIsRefused(String text) {
        assertFalse(TextGuard.isPlainText(ChatFixtures.utf8(text)), text + " must not pass as plain text");
    }

    @Test
    void markupLaterInTheTextIsJustText() {
        assertTrue(TextGuard.isPlainText(ChatFixtures.utf8("My log says <svg> was in the page")));
    }
}
