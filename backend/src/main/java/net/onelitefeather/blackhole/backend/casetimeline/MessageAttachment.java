package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A file or sticker of a message: metadata of one posted in a Discord ticket channel, or the entry of a chat file (an evidence item that a
 * message bound). Never the bytes, a storage key or a hash, and never the Discord CDN URL (a signed, expiring link is a credential); the
 * file name is personal data and goes with the message when it is redacted.
 *
 * @param kind        {@value #FILE} or {@value #STICKER}
 * @param id          the Discord attachment id, a snowflake
 * @param contentType the media type Discord reports
 * @param size        bytes
 * @param width       pixels, for images
 * @param height      pixels, for images
 * @param attachmentId the evidence item id of a file sent through the chat (the id the readers fetch it by); {@code null} for Discord metadata
 * @param provenance  {@code STAFF} or {@code PLAYER} for a chat file, {@code null} for Discord metadata
 */
public record MessageAttachment(String kind, @Nullable String id, @Nullable String name, @Nullable String contentType,
                                @Nullable Long size, @Nullable Integer width, @Nullable Integer height,
                                @Nullable String attachmentId, @Nullable String provenance) {

    /** Metadata of a Discord file or sticker, which has no chat file behind it. */
    public MessageAttachment(String kind, @Nullable String id, @Nullable String name, @Nullable String contentType,
                             @Nullable Long size, @Nullable Integer width, @Nullable Integer height) {
        this(kind, id, name, contentType, size, width, height, null, null);
    }

    public static final String FILE = "file";
    public static final String STICKER = "sticker";

    /** The JSON object stored in the message payload; entries without a value are left out. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", this.kind);
        put(map, "id", this.id);
        put(map, "name", this.name);
        put(map, "contentType", this.contentType);
        put(map, "size", this.size);
        put(map, "width", this.width);
        put(map, "height", this.height);
        put(map, "attachmentId", this.attachmentId);
        put(map, "provenance", this.provenance);
        return map;
    }

    private static void put(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
