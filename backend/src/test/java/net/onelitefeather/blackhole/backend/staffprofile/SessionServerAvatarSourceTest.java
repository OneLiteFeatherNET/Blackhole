package net.onelitefeather.blackhole.backend.staffprofile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A skin URL from a profile is used only for its texture hash, and only on the fixed texture host. */
class SessionServerAvatarSourceTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void theTextureHashOfAFixedHostSkinIsAccepted() {
        assertEquals(HASH, SessionServerAvatarSource.textureHash("https://textures.minecraft.net/texture/" + HASH).orElseThrow());
    }

    @Test
    void aSkinOnAnotherHostIsNotFollowed() {
        assertFalse(SessionServerAvatarSource.textureHash("https://evil.example/texture/" + HASH).isPresent());
    }

    @Test
    void aLookalikeHostIsNotFollowed() {
        assertFalse(SessionServerAvatarSource.textureHash("https://textures.minecraft.net.evil.example/texture/" + HASH).isPresent());
    }

    @Test
    void aPathThatIsNotATextureHashIsRefused() {
        assertFalse(SessionServerAvatarSource.textureHash("https://textures.minecraft.net/../admin").isPresent());
    }
}
