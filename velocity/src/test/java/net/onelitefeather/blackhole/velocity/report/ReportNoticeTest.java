package net.onelitefeather.blackhole.velocity.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportNoticeTest {

    @Test
    void theExcerptIsOneShortLine() {
        assertEquals("a b c", ReportNotice.excerptOf("a\n b\t c "));
        assertEquals("", ReportNotice.excerptOf(null));
        String excerpt = ReportNotice.excerptOf("x".repeat(500));
        assertEquals(ReportNotice.EXCERPT_LENGTH, excerpt.length());
        assertTrue(excerpt.endsWith("…"));
    }
}
