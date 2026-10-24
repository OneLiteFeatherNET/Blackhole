package net.onelitefeather.blackhole.backend.evidence.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.types.files.StreamedFile;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;

import java.util.Set;

/**
 * The one way a stored file leaves the backend: the detected type, no sniffing by the browser, a sandboxing CSP and no caching.
 * Only PNG, JPEG and WebP are shown {@code inline}; PDF, text and everything else is a download ({@code attachment}) under the sanitised
 * name. Staff evidence may also show video inline (the panel plays it); chat files never do.
 */
public final class SafeFileResponse {

    private static final Set<String> INLINE_IMAGES = Set.of("image/png", "image/jpeg", "image/webp");

    private SafeFileResponse() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Whether a file of this detected type may be shown in the page instead of downloaded. */
    public static boolean inline(String contentType, boolean videoInline) {
        return INLINE_IMAGES.contains(contentType) || (videoInline && contentType.startsWith("video/"));
    }

    /**
     * @param download    the opened file
     * @param videoInline whether a video is shown inline (staff evidence) or downloaded
     */
    public static HttpResponse<?> of(EvidenceDTO.Download download, boolean videoInline) {
        boolean inline = inline(download.contentType(), videoInline);
        StreamedFile body = new StreamedFile(download.stream(), MediaType.of(download.contentType()));
        MutableHttpResponse<StreamedFile> response = HttpResponse.<StreamedFile>status(download.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .body(body)
                .header("Content-Length", Long.toString(download.length()))
                .header("Accept-Ranges", "bytes")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Cache-Control", "private, no-store")
                .header("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + download.fileName() + "\"");
        return download.partial()
                ? response.header("Content-Range", "bytes " + download.start() + "-" + download.end() + "/" + download.total())
                : response;
    }
}
