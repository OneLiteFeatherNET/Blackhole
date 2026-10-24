package net.onelitefeather.blackhole.discord.testing;

import net.onelitefeather.blackhole.discord.attachment.AttachmentDownloader;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A CDN for tests: answers per URL from a script and remembers what was asked. No network. Each test builds its own. */
public final class FakeDownloader implements AttachmentDownloader {

    /** URL to answer; a URL that is not scripted fails. */
    public final Map<String, Download> answers = new HashMap<>();
    public final List<URI> requested = new ArrayList<>();
    public final List<Long> caps = new ArrayList<>();

    public void serve(String url, byte[] data) {
        this.answers.put(url, new Download.Bytes(data));
    }

    @Override
    public @NotNull Download get(@NotNull URI uri, long maxBytes) {
        this.requested.add(uri);
        this.caps.add(maxBytes);
        return this.answers.getOrDefault(uri.toString(), new Download.Failed());
    }
}
