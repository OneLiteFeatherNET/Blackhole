package net.onelitefeather.blackhole.discord.attachment;

import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * Downloads one file over HTTPS, never following a redirect and never reading more than a cap. The caller has already checked
 * the host; this is the seam tests replace so no network is used.
 */
public interface AttachmentDownloader {

    /** How a download ended. */
    sealed interface Download {

        /** @param data the whole file */
        record Bytes(byte @NotNull [] data) implements Download {
        }

        /** The server answered with a redirect, which is never followed. */
        record Redirected() implements Download {
        }

        /** The declared or the streamed size is above the cap. */
        record TooLarge() implements Download {
        }

        /** Any other failure: a status other than 200, a timeout, a broken connection. */
        record Failed() implements Download {
        }
    }

    /**
     * @param uri      an https URL on an allowed host
     * @param maxBytes the cap; a file of exactly this size is fine, one byte more is {@link Download.TooLarge}
     */
    @NotNull Download get(@NotNull URI uri, long maxBytes);
}
