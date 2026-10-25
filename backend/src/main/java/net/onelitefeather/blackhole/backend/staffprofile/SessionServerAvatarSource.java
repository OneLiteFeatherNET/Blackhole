package net.onelitefeather.blackhole.backend.staffprofile;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The production {@link StaffAvatarSource}: the account's profile from the configured session server
 * ({@code blackhole.staff.avatar.source-url}, validated as https at startup), then the skin from the fixed texture
 * host. The skin URL inside the profile is never followed as given: only its texture hash is used, and only on the
 * fixed host, so the response cannot steer the request elsewhere.
 */
@Context
@Singleton
public class SessionServerAvatarSource implements StaffAvatarSource {

    private static final Pattern TEXTURE_PATH = Pattern.compile("/texture/([0-9a-f]{64})");
    private static final String TEXTURE_HOST = "textures.minecraft.net";

    private final SessionServerClient sessions;
    private final TexturesClient textures;
    private final JsonMapper json;

    public SessionServerAvatarSource(SessionServerClient sessions, TexturesClient textures, JsonMapper json,
                                     @Value("${blackhole.staff.avatar.source-url}") String sourceUrl) {
        if (sourceUrl == null || !"https".equals(URI.create(sourceUrl).getScheme())) {
            throw new IllegalStateException("blackhole.staff.avatar.source-url must be an https URL, was: " + sourceUrl);
        }
        this.sessions = sessions;
        this.textures = textures;
        this.json = json;
    }

    @Override
    public Optional<byte[]> headPng(UUID minecraftUuid) {
        Optional<SessionProfile> profile = this.sessions.profile(minecraftUuid.toString().replace("-", ""));
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> hash = skinHash(profile.get());
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        byte[] skin = this.textures.skin(hash.get());
        try {
            return Optional.of(StaffHeadRenderer.head64(skin));
        } catch (IllegalArgumentException notASkin) {
            return Optional.empty();
        }
    }

    private Optional<String> skinHash(SessionProfile profile) {
        for (SessionProfile.Property property : profile.properties()) {
            if (!"textures".equals(property.name()) || property.value() == null) {
                continue;
            }
            try {
                String decoded = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
                Map<String, Object> root = this.json.readValue(decoded, Argument.mapOf(String.class, Object.class));
                if (root.get("textures") instanceof Map<?, ?> textures && textures.get("SKIN") instanceof Map<?, ?> skin
                        && skin.get("url") instanceof String url) {
                    return textureHash(url);
                }
            } catch (IllegalArgumentException | java.io.IOException malformed) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The hash of a skin URL on the texture host, or empty for any other host or path. */
    static Optional<String> textureHash(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (!TEXTURE_HOST.equals(uri.getHost()) || uri.getPath() == null) {
            return Optional.empty();
        }
        Matcher matcher = TEXTURE_PATH.matcher(uri.getPath());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The session server's profile of an account. Mojang answers 204 for an unknown UUID. */
    @Client(id = "staff-avatar-session", value = "${blackhole.staff.avatar.source-url}")
    public interface SessionServerClient {

        @Get("/session/minecraft/profile/{uuid}")
        Optional<SessionProfile> profile(String uuid);
    }

    /** The skin texture of a hash on the fixed texture host. */
    @Client(id = "staff-avatar-textures", value = "https://textures.minecraft.net")
    public interface TexturesClient {

        @Get("/texture/{hash}")
        byte[] skin(String hash);
    }

    @Serdeable
    public record SessionProfile(String id, List<Property> properties) {

        @Serdeable
        public record Property(String name, String value) {
        }
    }
}
