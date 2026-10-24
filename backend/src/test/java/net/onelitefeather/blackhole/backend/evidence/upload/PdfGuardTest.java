package net.onelitefeather.blackhole.backend.evidence.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfGuardTest {

    @Test
    void aPlainPdfPasses() {
        assertEquals(PdfGuard.Verdict.OK, PdfGuard.check(ChatFixtures.pdf("/Pages 2 0 R")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/JavaScript", "/JS", "/Launch", "/OpenAction", "/AA", "/EmbeddedFile", "/RichMedia", "/XFA", "/SubmitForm", "/Encrypt"})
    void everyForbiddenNameIsRefused(String name) {
        assertEquals(PdfGuard.Verdict.ACTIVE_CONTENT, PdfGuard.check(ChatFixtures.pdf(name + " 5 0 R")), name + " must be refused");
    }

    @Test
    void anEscapedNameIsReadTheWayAReaderReadsIt() {
        assertEquals(PdfGuard.Verdict.ACTIVE_CONTENT, PdfGuard.check(ChatFixtures.pdf("/#4AavaScript 5 0 R")), "/#4A is J");
        assertEquals(PdfGuard.Verdict.ACTIVE_CONTENT, PdfGuard.check(ChatFixtures.pdf("/Open#41ction 5 0 R")), "/Open#41ction is /OpenAction");
    }

    @Test
    void aNameThatOnlyStartsLikeAForbiddenOneIsFine() {
        assertEquals(PdfGuard.Verdict.OK, PdfGuard.check(ChatFixtures.pdf("/JSON 1 /AAA 2 /Launcher 3")), "names end at a delimiter");
    }

    @Test
    void aNameInsideTextIsStillFoundWhenFollowedByADelimiter() {
        assertEquals(PdfGuard.Verdict.ACTIVE_CONTENT, PdfGuard.check(ChatFixtures.pdf("/S/JavaScript/JS(alert(1))")));
    }

    @Test
    void aFileWithoutTheHeaderIsNotAPdf() {
        assertEquals(PdfGuard.Verdict.NOT_A_PDF, PdfGuard.check("hello".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theHeaderMustBeInTheFirstKilobyte() {
        byte[] late = new byte[1100];
        byte[] header = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, late, 1050, header.length);

        assertEquals(PdfGuard.Verdict.NOT_A_PDF, PdfGuard.check(late));
    }
}
