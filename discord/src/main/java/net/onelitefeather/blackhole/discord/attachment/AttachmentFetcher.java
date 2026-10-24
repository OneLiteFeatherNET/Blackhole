package net.onelitefeather.blackhole.discord.attachment;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * The download policy for a file a Discord participant posted (the SSRF hardening of the chat design): the URL must be https on
 * one of two exact hosts, the declared size and the streamed size are capped, and a redirect is a refusal. The URL is never
 * logged or kept.
 */
public final class AttachmentFetcher {

    /** The only hosts a Discord attachment is served from. */
    public static final Set<String> HOSTS = Set.of("cdn.discordapp.com", "media.discordapp.net");
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    /** Files of one message that are downloaded; the rest are recorded as {@link #QUOTA}. */
    public static final int MAX_FILES = 5;

    public static final String TOO_LARGE = "TOO_LARGE";
    public static final String FILE_NOT_ACCEPTED = "FILE_NOT_ACCEPTED";
    public static final String QUOTA = "QUOTA";

    /** What the fetch produced. */
    public sealed interface Fetched {

        /** @param data the file */
        record Bytes(byte @NotNull [] data) implements Fetched {
        }

        /** @param code the reject code to record: {@code TOO_LARGE} or {@code FILE_NOT_ACCEPTED} */
        record Rejected(@NotNull String code) implements Fetched {
        }
    }

    private final AttachmentDownloader downloader;

    public AttachmentFetcher(@NotNull AttachmentDownloader downloader) {
        this.downloader = downloader;
    }

    /**
     * @param url          the CDN link of the attachment
     * @param declaredSize the size Discord reports, or null
     */
    public @NotNull Fetched fetch(@Nullable String url, @Nullable Long declaredSize) {
        if (declaredSize != null && declaredSize > MAX_BYTES) {
            return new Fetched.Rejected(TOO_LARGE);
        }
        URI uri = allowed(url);
        if (uri == null) {
            return new Fetched.Rejected(FILE_NOT_ACCEPTED);
        }
        return switch (this.downloader.get(uri, MAX_BYTES)) {
            case AttachmentDownloader.Download.Bytes bytes -> new Fetched.Bytes(bytes.data());
            case AttachmentDownloader.Download.TooLarge ignored -> new Fetched.Rejected(TOO_LARGE);
            case AttachmentDownloader.Download.Redirected ignored -> new Fetched.Rejected(FILE_NOT_ACCEPTED);
            case AttachmentDownloader.Download.Failed ignored -> new Fetched.Rejected(FILE_NOT_ACCEPTED);
        };
    }

    /** @return the URI when it is https on an allowed host with the default port and no user info, else null */
    static @Nullable URI allowed(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
                return null;
            }
            if (uri.getPort() != -1 && uri.getPort() != 443) {
                return null;
            }
            return HOSTS.contains(host.toLowerCase(Locale.ROOT)) ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
