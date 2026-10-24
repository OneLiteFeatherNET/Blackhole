package net.onelitefeather.blackhole.discord.attachment;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@link AttachmentDownloader} over the JDK HTTP client: redirects off, connect and read timeouts, a streamed size cap. */
public final class JdkAttachmentDownloader implements AttachmentDownloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    public JdkAttachmentDownloader() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build());
    }

    /** @param client a client that must not follow redirects */
    JdkAttachmentDownloader(@NotNull HttpClient client) {
        this.client = client;
    }

    @Override
    public @NotNull Download get(@NotNull URI uri, long maxBytes) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            HttpResponse<InputStream> response = this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    return new Download.Redirected();
                }
                if (status != 200) {
                    return new Download.Failed();
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maxBytes) {
                    return new Download.TooLarge();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(32, Math.min(declared, maxBytes)));
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = body.read(buffer)) >= 0) {
                    total += read;
                    if (total > maxBytes) {
                        return new Download.TooLarge();
                    }
                    out.write(buffer, 0, read);
                }
                return new Download.Bytes(out.toByteArray());
            }
        } catch (IOException e) {
            return new Download.Failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Download.Failed();
        } catch (RuntimeException e) {
            return new Download.Failed();
        }
    }
}
