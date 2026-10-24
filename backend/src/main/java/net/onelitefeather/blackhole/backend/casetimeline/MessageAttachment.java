package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata of a file or sticker posted in a Discord ticket channel. Never the file and never the
 * Discord CDN URL (a signed, expiring link is a credential); the file name is personal data and goes
 * with the message when it is redacted.
 *
 * @param kind        {@value #FILE} or {@value #STICKER}
 * @param id          the Discord attachment id, a snowflake
 * @param contentType the media type Discord reports
 * @param size        bytes
 * @param width       pixels, for images
 * @param height      pixels, for images
 */
public record MessageAttachment(String kind, @Nullable String id, @Nullable String name, @Nullable String contentType,
                                @Nullable Long size, @Nullable Integer width, @Nullable Integer height) {

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
        return map;
    }

    private static void put(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
