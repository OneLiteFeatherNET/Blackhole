package net.onelitefeather.blackhole.backend.evidence.controller;

import io.micronaut.http.HttpResponse;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeFileResponseTest {

    private static HttpResponse<?> serve(String contentType, String name, boolean videoInline) {
        return SafeFileResponse.of(new EvidenceDTO.Download(new ByteArrayInputStream(new byte[10]), 10, 0, 9, 10, false, contentType, name), videoInline);
    }

    @ParameterizedTest
    @CsvSource({
            "image/png,inline",
            "image/jpeg,inline",
            "image/webp,inline",
            "application/pdf,attachment",
            "text/plain,attachment",
            "image/svg+xml,attachment",
            "text/html,attachment",
            "video/mp4,attachment"
    })
    void onlyPngJpegAndWebpAreShownInline(String contentType, String disposition) {
        HttpResponse<?> response = serve(contentType, "file.bin", false);

        assertEquals(disposition + "; filename=\"file.bin\"", response.header("Content-Disposition"), contentType);
    }

    @ParameterizedTest
    @CsvSource({"image/png", "application/pdf", "text/plain", "image/webp"})
    void everyFileCarriesTheSafetyHeaders(String contentType) {
        HttpResponse<?> response = serve(contentType, "file.bin", false);

        assertEquals("nosniff", response.header("X-Content-Type-Options"));
        assertEquals("sandbox", response.header("Content-Security-Policy"));
        assertEquals("private, no-store", response.header("Cache-Control"));
        assertEquals("10", response.header("Content-Length"));
    }

    @Test
    void staffEvidenceMayShowVideoInlineButAChatNever() {
        assertEquals("inline; filename=\"c.mp4\"", serve("video/mp4", "c.mp4", true).header("Content-Disposition"));
        assertEquals("attachment; filename=\"c.mp4\"", serve("video/mp4", "c.mp4", false).header("Content-Disposition"));
    }

    @Test
    void aRangeAnswerKeepsItsHeaders() {
        HttpResponse<?> response = SafeFileResponse.of(new EvidenceDTO.Download(new ByteArrayInputStream(new byte[5]), 5, 0, 4, 10, true,
                "application/pdf", "a.pdf"), false);

        assertEquals(206, response.code());
        assertEquals("bytes 0-4/10", response.header("Content-Range"));
        assertEquals("attachment; filename=\"a.pdf\"", response.header("Content-Disposition"));
    }
}
