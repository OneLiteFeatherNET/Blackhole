package net.onelitefeather.blackhole.backend.staffprofile;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;

/**
 * The public key of a staff head: {@code base64url(HMAC-SHA256(secret, "avatar:" + minecraftUuid))}, cut to 22
 * characters. The key is stable for an account, differs between accounts, and reveals neither the UUID nor the
 * account, so it is the only identifier a player's browser ever sees for a staff avatar.
 */
@Singleton
public class StaffAvatarKeys {

    /** The length of a key in characters. */
    public static final int LENGTH = 22;

    private static final String PREFIX = "avatar:";
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec secret;

    public StaffAvatarKeys(@Value("${blackhole.staff.avatar.key}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("BLACKHOLE_STAFF_AVATAR_KEY must be set");
        }
        this.secret = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** The avatar key of a Minecraft account. */
    public String keyFor(UUID minecraftUuid) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(this.secret);
            byte[] digest = mac.doFinal((PREFIX + minecraftUuid).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
