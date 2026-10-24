package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An embed in a Discord ticket channel: its type and the host of its URL only. Titles and descriptions
 * come from third-party pages and are not stored.
 */
public record MessageEmbed(String type, @Nullable String host) {

    /** The JSON object stored in the message payload. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", this.type);
        if (this.host != null) {
            map.put("host", this.host);
        }
        return map;
    }
}
