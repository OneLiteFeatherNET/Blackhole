package net.onelitefeather.blackhole.backend.staffprofile;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a linked account's head comes from. The production implementation asks the configured session server;
 * tests use a fake, so no test touches the network. Called only by the backend, never from a player's browser.
 */
public interface StaffAvatarSource {

    /**
     * @return the head as a PNG, or empty when the account has no skin
     * @throws RuntimeException when the source cannot be reached; the caller keeps the cached head
     */
    Optional<byte[]> headPng(UUID minecraftUuid);
}
