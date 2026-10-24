package net.onelitefeather.blackhole.velocity.report;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportNoticeTest {

    @Test
    void aNoticeSurvivesTheWire() {
        ReportNotice notice = new ReportNotice("id-1", "Alex", "Steve", "CHAT_ABUSE", "rude");

        assertEquals(Optional.of(notice), ReportNotice.decode(ReportNotice.encode(notice)));
    }

    @Test
    void unknownFieldsAreIgnoredSoNewerProxiesCanAddFields() {
        Optional<ReportNotice> decoded = ReportNotice.decode(
                "{\"reportId\":null,\"reporter\":\"A\",\"reported\":\"B\",\"category\":\"OTHER\",\"excerpt\":\"x\",\"future\":1}");

        assertEquals(Optional.of(new ReportNotice(null, "A", "B", "OTHER", "x")), decoded);
    }

    @Test
    void aMissingExcerptBecomesEmpty() {
        assertEquals("", ReportNotice.decode("{\"reporter\":\"A\",\"reported\":\"B\",\"category\":\"OTHER\"}").orElseThrow().excerpt());
    }

    @Test
    void garbageAndIncompleteMessagesAreRejected() {
        assertTrue(ReportNotice.decode("not json").isEmpty());
        assertTrue(ReportNotice.decode("{\"reporter\":\"A\"}").isEmpty(), "a notice without the reported player is useless");
        assertTrue(ReportNotice.decode(null).isEmpty());
    }

    @Test
    void theExcerptIsOneShortLine() {
        assertEquals("a b c", ReportNotice.excerptOf("a\n b\t c "));
        assertEquals("", ReportNotice.excerptOf(null));
        String excerpt = ReportNotice.excerptOf("x".repeat(500));
        assertEquals(ReportNotice.EXCERPT_LENGTH, excerpt.length());
        assertTrue(excerpt.endsWith("…"));
    }
}
